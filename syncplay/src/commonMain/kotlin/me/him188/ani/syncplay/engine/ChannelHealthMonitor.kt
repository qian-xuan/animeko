package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.models.ConnectionState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Channel-health monitoring: 3 coroutines that run while CONNECTED.
 *
 * Ported from syncplay-mobile `ProtocolManager.kt:183-251`, adapted to use
 * an injected [MutableStateFlow] for play-state instead of a player dependency.
 *
 * - **List-probe** — sends an empty `List` request every [ProtocolManager.LIST_PROBE_INTERVAL_SECONDS]
 *   seconds to keep the channel warm. A broken socket surfaces early: if the send
 *   fails, the network manager's retry path flips state to DISCONNECTED.
 * - **State watchdog** — runs every [ProtocolManager.WATCHDOG_INTERVAL_SECONDS] seconds.
 *   If no State message has arrived for [ProtocolManager.STATE_TIMEOUT_SECONDS] seconds
 *   while still CONNECTED, fires [SyncplayNetworkManager.terminateExistingConnection] +
 *   [SyncplayNetworkManager.onDisconnected] to kick off a reconnect. Detects silent
 *   disconnects where the socket looks healthy locally but the server stopped sending.
 * - **Playback-broadcast** — collects [isPlayingFlow] and emits a corrective `State`
 *   when the engine's actual play-state diverges from [ProtocolManager.expectedPlaying].
 *   No separate 1s position heartbeat — position is reported via ACKs in `onState`.
 *
 * @param scope the controller's scope — owns the 3 coroutines.
 * @param networkManager for sending List requests and checking connection state.
 * @param protocol for reading `lastStateReceivedAt`, `expectedPlaying`, and building State packets.
 * @param isPlayingFlow the engine's play-state input — the bridge (T4.3) writes to this,
 *   the playback-broadcast coroutine reads it. Maps `player.playbackState -> Boolean`.
 * @param coroutineDispatcher dispatcher for the 3 coroutines. Defaults to [Dispatchers.IO];
 *   tests inject a [kotlinx.coroutines.test.TestDispatcher] for virtual-time control.
 */
class ChannelHealthMonitor(
    private val scope: CoroutineScope,
    private val networkManager: SyncplayNetworkManager,
    private val protocol: ProtocolManager,
    val isPlayingFlow: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var listProbeJob: Job? = null
    private var watchdogJob: Job? = null
    private var playbackBroadcastJob: Job? = null

    /**
     * Starts the 3 coroutines. Called when the connection becomes CONNECTED.
     * Idempotent — stops existing jobs before starting new ones.
     *
     * Seeds [ProtocolManager.lastStateReceivedAt] to `now` so the watchdog doesn't
     * immediately fire before any State arrives.
     */
    fun start() {
        stop()
        protocol.lastStateReceivedAt = Clock.System.now()

        // 1. List-probe: send List request every 15s to keep the channel warm.
        listProbeJob = scope.launch(coroutineDispatcher) {
            while (isActive) {
                delay(ProtocolManager.LIST_PROBE_INTERVAL_SECONDS.seconds)
                if (networkManager.state.value == ConnectionState.CONNECTED) {
                    networkManager.sendAsync(WireMessage.listRequest())
                }
            }
        }

        // 2. Watchdog: every 5s, check if no State for 15s -> disconnect.
        watchdogJob = scope.launch(coroutineDispatcher) {
            while (isActive) {
                delay(ProtocolManager.WATCHDOG_INTERVAL_SECONDS.seconds)
                val lastState = protocol.lastStateReceivedAt
                if (lastState != null && networkManager.state.value == ConnectionState.CONNECTED) {
                    val elapsed = (Clock.System.now() - lastState).inWholeSeconds
                    if (elapsed >= ProtocolManager.STATE_TIMEOUT_SECONDS) {
                        // Silent disconnect — server stopped sending State.
                        // Drop the stale socket before firing the callback so the
                        // reconnect logic doesn't try to reuse a dead connection.
                        networkManager.terminateExistingConnection()
                        networkManager.onDisconnected()
                        break
                    }
                }
            }
        }

        // 3. Playback-broadcast: emit State when isPlayingFlow diverges from expectedPaused.
        // No separate 1s heartbeat — position is reported via ACKs in onState (server-driven cycle).
        playbackBroadcastJob = scope.launch(coroutineDispatcher) {
            isPlayingFlow.collect { isPlaying ->
                val expectedPaused = !protocol.expectedPlaying
                val actualPaused = !isPlaying
                if (expectedPaused != actualPaused &&
                    networkManager.state.value == ConnectionState.CONNECTED
                ) {
                    // Divergence: the player's actual state doesn't match what we told the room.
                    // Update expectation BEFORE sending so the next emission doesn't re-fire.
                    protocol.noteExpectedPlaybackState(paused = actualPaused)
                    // isLocalStateChange = true increments clientIgnFly, so the server echo
                    // is ignored by the anti-feedback logic (T3.3).
                    networkManager.sendAsync(
                        protocol.buildStatePacket(
                            serverTime = null,
                            doSeek = null,
                            position = null,
                            isLocalStateChange = true,
                            play = isPlaying,
                        )
                    )
                }
            }
        }
    }

    /** Stops all 3 coroutines. Safe to call multiple times. Called on disconnect. */
    fun stop() {
        listProbeJob?.cancel()
        watchdogJob?.cancel()
        playbackBroadcastJob?.cancel()
        listProbeJob = null
        watchdogJob = null
        playbackBroadcastJob = null
    }
}
