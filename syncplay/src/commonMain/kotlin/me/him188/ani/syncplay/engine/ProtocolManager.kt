package me.him188.ani.syncplay.engine

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.models.PingService
import me.him188.ani.syncplay.protocol.wire.IgnoringOnTheFlyData
import me.him188.ani.syncplay.protocol.wire.PingData
import me.him188.ani.syncplay.protocol.wire.PlaystateData
import me.him188.ani.syncplay.protocol.wire.StateData
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Holds the room's global playback state, the `ignoringOnTheFly` anti-feedback counters,
 * and builds outbound `State` packets.
 *
 * Ported from syncplay-mobile `app.protocol.ProtocolManager`, stripped of the
 * `RoomViewmodel` dependency, channel-health monitoring, and `reportableStatePositionSec`
 * (those are T3.6 / T4.3 scope). This class is standalone — the engine/controller owns
 * an instance and updates its fields directly.
 */
class ProtocolManager {

    var globalPaused: Boolean = true
    var globalPositionMs: Double = 0.0

    /**
     * The wall-clock time at which [globalPositionMs] was last set from a server `State`.
     * Used to extrapolate the room's *current* expected position.
     */
    var lastGlobalPositionSetAt: Instant? = null

    /** Tracks conflicting state updates during rapid changes. Updated atomically. */
    private val _serverIgnFly = atomic(0)
    var serverIgnFly: Int
        get() = _serverIgnFly.value
        set(value) { _serverIgnFly.value = value }

    /**
     * Prevents responding to our own state changes until the server acknowledges them.
     * Atomic because both [buildStatePacket] (called from IO on every user action) and
     * the player polling loop can race on the increment-and-send sequence.
     */
    private val _clientIgnFly = atomic(0)
    var clientIgnFly: Int
        get() = _clientIgnFly.value
        set(value) { _clientIgnFly.value = value }

    /**
     * Position drift threshold (seconds) at which the client triggers a corrective rewind.
     * Matches PC's `DEFAULT_REWIND_THRESHOLD = 4`.
     */
    val rewindThreshold = REWIND_THRESHOLD

    /**
     * Timestamp of the last received global state update. A `null` value arms the
     * one-shot "first sync" (force-seek to the room position on the next inbound `State`).
     */
    @Volatile
    var lastGlobalUpdate: Instant? = null

    /**
     * Position-masking window for a freshly-loaded file: non-null (a future instant)
     * means we are still catching up and every outbound `State` must advertise the
     * room's extrapolated position instead of the engine's own.
     */
    @Volatile
    var awaitingRoomResyncDeadline: Instant? = null

    var pingService = PingService()

    /** Tracks whether playback speed has been adjusted for desync correction. */
    var speedChanged = false

    /** Timestamp when we first detected the client is behind. Null if not behind. */
    var behindFirstDetected: Instant? = null

    /** Set during room transitions to ignore stale packets from the previous room. */
    var isRoomChanging = false

    val supportsChat = MutableStateFlow(true)
    val supportsManagedRooms = MutableStateFlow(false)
    val isManagedRoom = MutableStateFlow(false)

    /**
     * Timestamp of the last `State` message received from the server. The watchdog
     * (T3.6) uses this to detect silent disconnects.
     */
    @Volatile
    var lastStateReceivedAt: Instant? = null

    /**
     * Our current belief about the player's pause state. Outbound paths read this
     * instead of probing the player engine, which may report a stale value in the
     * async window right after a pause/play toggle.
     */
    private var expectedPaused: Boolean = true

    /** The room's *intended* play state as the app authoritatively knows it. */
    val expectedPlaying: Boolean get() = !expectedPaused

    /**
     * Records what we expect the player's pause state to be after a deliberate change.
     * Must be called BEFORE invoking the player's pause/play so the engine callback's
     * resulting emission matches our expectation and is not re-broadcast.
     */
    fun noteExpectedPlaybackState(paused: Boolean) {
        expectedPaused = paused
    }

    /**
     * Arms the position-masking window for a freshly-loaded file. Called before
     * `media.value` is set, so an inbound-State ACK can never observe media!=null with
     * the mask still disarmed.
     */
    fun markAwaitingRoomResync() {
        awaitingRoomResyncDeadline = Clock.System.now() + AWAITING_ROOM_RESYNC_TIMEOUT_SECONDS.seconds
    }

    /**
     * Builds an outbound `State` packet, applying the same `ignoringOnTheFly`
     * bookkeeping (mutating [serverIgnFly] / [clientIgnFly] as side effects) as the
     * python reference client.
     *
     * [position] is full-precision seconds (Double) on the wire — never round to whole
     * seconds. Negative or NaN values are clamped to 0.0 to avoid advertising invalid
     * positions.
     */
    fun buildStatePacket(
        serverTime: Double?,
        doSeek: Boolean?,
        position: Double?,
        isLocalStateChange: Boolean,
        play: Boolean?,
    ): WireMessage.State {
        val clientIgnoreIsNotSet = clientIgnFly == 0 || serverIgnFly != 0

        val safePosition = position?.let { pos ->
            when {
                pos.isNaN() || pos < 0 -> 0.0
                else -> pos
            }
        }

        val playstate = if (clientIgnoreIsNotSet && safePosition != null && play != null) {
            PlaystateData(
                position = safePosition,
                paused = !play,
                doSeek = doSeek
            )
        } else null

        val ping = PingData(
            latencyCalculation = serverTime,
            clientLatencyCalculation = Clock.System.now().toEpochMilliseconds() / 1000.0,
            clientRtt = pingService.rtt
        )

        if (isLocalStateChange) {
            logger.debug { "Outbound State: doSeek=$doSeek, pos=$position, play=$play, clientIgnFly=${clientIgnFly + 1}" }
            _clientIgnFly.incrementAndGet()
        }

        val snapshotServer = _serverIgnFly.value
        val snapshotClient = _clientIgnFly.value
        val ignoring = if (snapshotClient != 0 || snapshotServer != 0) {
            val ign = IgnoringOnTheFlyData(
                server = snapshotServer.takeIf { it != 0 },
                client = snapshotClient.takeIf { it != 0 }
            )
            if (snapshotServer != 0) {
                logger.debug { "serverIgnFly reset: $snapshotServer to 0 (in ACK)" }
                _serverIgnFly.compareAndSet(snapshotServer, 0)
            }
            ign
        } else null

        return WireMessage.State(
            StateData(playstate = playstate, ping = ping, ignoringOnTheFly = ignoring)
        )
    }

    companion object {
        private val logger = logger<ProtocolManager>()

        /** Syncplay protocol version advertised in `Hello.realversion`. */
        const val SYNCPLAY_PROTOCOL_VERSION = "1.7.5"

        /** Legacy compatibility version advertised in `Hello.version`. */
        const val SYNCPLAY_LEGACY_VERSION = "1.2.255"

        /** Playback drift threshold in seconds before a corrective seek is triggered. */
        const val SEEK_THRESHOLD = 1L

        /** Playback speed used to gradually catch up when ahead of others. */
        const val SLOWDOWN_RATE = 0.95

        /** Time difference (seconds) at which slowdown kicks in. */
        const val SLOWDOWN_THRESHOLD = 1.5

        /** Time difference (seconds) at which speed reverts to normal. */
        const val SLOWDOWN_RESET_THRESHOLD = 0.1

        /** Time difference (seconds, negative/behind) at which fastforward detection starts. */
        const val FASTFORWARD_BEHIND_THRESHOLD = 1.75

        /** Time difference (seconds, behind) at which fastforward triggers after waiting. */
        const val FASTFORWARD_THRESHOLD = 5.0

        /** Extra time (seconds) added when fastforwarding to overshoot slightly. */
        const val FASTFORWARD_EXTRA_TIME = 0.25

        /** Cooldown (seconds) after a fastforward before it can trigger again. */
        const val FASTFORWARD_RESET_THRESHOLD = 3.0

        /** How often the list-probe coroutine fires an empty List to keep the channel warm. */
        const val LIST_PROBE_INTERVAL_SECONDS = 15L

        /** How often the State watchdog checks whether the server has gone silent. */
        const val WATCHDOG_INTERVAL_SECONDS = 5L

        /** If no State message has arrived in this many seconds, assume the channel is broken. */
        const val STATE_TIMEOUT_SECONDS = 15L

        /** Max seconds a freshly-loaded file may advertise the room position while catching up. */
        const val AWAITING_ROOM_RESYNC_TIMEOUT_SECONDS = 30L

        /** Position drift threshold (seconds) for corrective rewind. */
        const val REWIND_THRESHOLD = 4L
    }
}
