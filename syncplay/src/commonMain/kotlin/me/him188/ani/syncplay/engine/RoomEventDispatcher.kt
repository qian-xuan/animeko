package me.him188.ani.syncplay.engine

import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.models.RoomFeatures
import me.him188.ani.syncplay.protocol.wire.HelloData
import me.him188.ani.syncplay.protocol.wire.Room
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.logger

/**
 * Handles outbound protocol messages for playback control, seeking, and chat.
 * Counterpart to [RoomCallback].
 *
 * Ported from syncplay-mobile `RoomEventDispatcher.kt:32-255`, stripped of
 * viewmodel dependencies, solo-mode gating, readiness checks, background-pause
 * gating, and UI convenience methods (seekBckwd/seekFrwrd, broadcastMessage).
 * Takes a [SyncplayNetworkManager], [Session], and [ProtocolManager] via
 * constructor.
 *
 * The `sendHello()` method duplicates the one in [SyncplayController] (T2.3).
 * That is intentional — the dispatcher is the canonical outbound path; in T4.1
 * the controller will delegate to the dispatcher instead of carrying its own copy.
 */
class RoomEventDispatcher(
    private val networkManager: SyncplayNetworkManager,
    private val session: Session,
    private val protocol: ProtocolManager,
) {
    private val network get() = networkManager

    companion object {
        private val logger = logger<RoomEventDispatcher>()
    }

    /**
     * Sends the Hello handshake message.
     *
     * Ported from syncplay-mobile RoomEventDispatcher.sendHello() (lines 36-53).
     * Uses [md5Hex] for password hashing. `version` carries the 1.2.x legacy
     * compatibility constant; `realversion` carries the actual protocol version
     * (from [ProtocolManager] companion — the canonical source, not [SyncplayController]
     * whose companion may carry a different value).
     */
    suspend fun sendHello() {
        logger.debug {
            "Sending Hello: room='${session.currentRoom}', user='${session.currentUsername}', version=${ProtocolManager.SYNCPLAY_LEGACY_VERSION}, realversion=${ProtocolManager.SYNCPLAY_PROTOCOL_VERSION}"
        }
        val passwordHash = session.currentPassword.takeIf { it.isNotEmpty() }?.let { md5Hex(it) }
        network.send(
            WireMessage.Hello(
                HelloData(
                    username = session.currentUsername,
                    password = passwordHash,
                    room = Room(session.currentRoom),
                    version = ProtocolManager.SYNCPLAY_LEGACY_VERSION,
                    realversion = ProtocolManager.SYNCPLAY_PROTOCOL_VERSION,
                    features = RoomFeatures(),
                )
            )
        )
    }

    /**
     * Sends a seek State packet.
     *
     * Ported from syncplay-mobile RoomEventDispatcher.sendSeek() (lines 74-96).
     * Sets `doSeek=true`, `position=newPosMs/1000.0`, `play=protocol.expectedPlaying`.
     *
     * A seek never changes pause state, so the truthful `play` value is the room's
     * already-known intent ([ProtocolManager.expectedPlaying]) — NOT a live
     * `player.isPlaying()` probe, which may return a stale value right after a
     * pause/play toggle on some engines (notably VLCKit 4).
     */
    suspend fun sendSeek(newPosMs: Long) {
        val positionSec = newPosMs / 1000.0
        val play = protocol.expectedPlaying
        logger.debug { "Sending seek to ${newPosMs}ms (play=$play)" }
        network.send(
            protocol.buildStatePacket(
                serverTime = null,
                doSeek = true,
                position = positionSec,
                isLocalStateChange = true,
                play = play,
            )
        )
    }

    /**
     * Sends a chat message.
     *
     * Ported from syncplay-mobile RoomEventDispatcher.sendMessage() (lines 98-101).
     */
    suspend fun sendMessage(message: String) {
        logger.debug { "Sending chat: $message" }
        network.send(WireMessage.chatRequest(message))
    }

    /**
     * Controls playback (pause/play) and optionally tells the server.
     *
     * Ported from syncplay-mobile RoomEventDispatcher.controlPlayback() (lines 103-175),
     * stripped of background-pause gating, readiness checks, and the direct engine
     * call (player.pause()/play()). The engine call is T4.x scope where the
     * dispatcher is wired to a player abstraction.
     *
     * [ProtocolManager.noteExpectedPlaybackState] is called BEFORE sending the State
     * packet, matching the syncplay-mobile pattern: the engine's isNowPlaying callback
     * fires synchronously inside player.pause()/play() on some engines (notably
     * ExoPlayer), and the protocol's flow collector reads this expectation to decide
     * whether to re-broadcast the change. Setting it after would race the collector
     * against the stale expectation and broadcast a redundant State packet.
     *
     * @param playback PLAY or PAUSE
     * @param tellServer if true, sends a State packet with the new pause state
     */
    suspend fun controlPlayback(playback: Playback, tellServer: Boolean = true) {
        val paused = playback == Playback.PAUSE
        logger.debug { "Control playback: $playback (tellServer=$tellServer)" }
        protocol.noteExpectedPlaybackState(paused = paused)
        if (tellServer) {
            network.send(
                protocol.buildStatePacket(
                    serverTime = null,
                    doSeek = null,
                    // Position defaults to 0.0; the reportable-position helper
                    // (protocol.reportableStatePositionSec) is T4.3 scope.
                    position = 0.0,
                    isLocalStateChange = true,
                    play = !paused,
                )
            )
        }
    }
}

enum class Playback { PLAY, PAUSE }
