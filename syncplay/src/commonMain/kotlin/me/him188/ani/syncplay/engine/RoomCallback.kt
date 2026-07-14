package me.him188.ani.syncplay.engine

/**
 * Inbound reaction interface — the message handler (T3.4) calls these methods
 * to notify the engine of room events. The controller/engine implements this
 * to drive the player and update UI state.
 *
 * Ported from syncplay-mobile `RoomCallback.kt:58-431`, stripped of viewmodel
 * dependencies (haptics, OSD, resource strings, readiness gating, player calls).
 * Each method defaults to no-op so implementations override only the events
 * they care about.
 *
 * The full syncplay-mobile `RoomCallback` also includes `onReceivedList`,
 * `onSomeoneLoadedFile`, `onPlaylistUpdated`, `onPlaylistIndexChanged`,
 * `onConnectionAttempt`, `onConnectionFailed`, `onTLSCheck`, `onReceivedTLS`,
 * `onNewControlledRoom`, and `onHandleControllerAuth`. Those are deferred to
 * later tasks (T3.4 message handler, T4.x controller wiring) where the
 * supporting infrastructure (playlist manager, TLS negotiation, controlled-room
 * auth) exists.
 */
interface RoomCallback {
    suspend fun onSomeonePaused(setBy: String) {}
    suspend fun onSomeonePlayed(setBy: String) {}
    suspend fun onSomeoneSeeked(setBy: String, positionSec: Double) {}
    suspend fun onSomeoneBehind(setBy: String, positionSec: Double) {}
    suspend fun onSomeoneFastForwarded(setBy: String, positionSec: Double) {}
    suspend fun onSomeoneJoined(username: String) {}
    suspend fun onSomeoneLeft(username: String) {}
    suspend fun onChatReceived(username: String, message: String) {}
    suspend fun onConnected() {}
    suspend fun onDisconnected() {}
}
