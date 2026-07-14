package me.him188.ani.syncplay.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.syncplay.protocol.models.RoomFeatures
import me.him188.ani.syncplay.protocol.models.User
import me.him188.ani.syncplay.protocol.wire.FileData

/**
 * Minimal session state for a Syncplay connection — holds connection parameters, the
 * room's user list, a chat message log, and an outbound replay queue.
 *
 * Ported from syncplay-mobile `app.protocol.Session` (lines 15-81), stripped of:
 * - `ProtocolManager` dependency (Wave 3 — sync engine)
 * - Compose state types (`mutableStateOf`, `mutableStateListOf`, `mutableIntStateOf`)
 *   — replaced with `MutableStateFlow` since `:syncplay` has no Compose dependency
 * - Shared playlist / playback index (Wave 3+ scope)
 *
 * The outbound queue is guarded by a [Mutex] because the failure path of
 * `transmitPacket` appends from IO threads while `onConnected` drains, so the
 * snapshot-then-clear in [drainOutbound] must be atomic against concurrent appends.
 */
class Session {
    var serverHost: String = ""
    var serverPort: Int = 0
    var currentUsername: String = "Anonymous"
    var currentRoom: String = ""
    var currentPassword: String = ""

    /** Server-advertised feature flags; overwritten by the Hello response. */
    var roomFeatures: RoomFeatures = RoomFeatures()

    val userList = MutableStateFlow(listOf<User>())

    /** Chat message log — appended on each inbound chat broadcast. */
    val messageSequence = MutableStateFlow<List<ChatMessage>>(emptyList())

    /**
     * Inbound file-change events from other peers (via `Set.user[username].file`).
     *
     * `replay = 1` so the bridge's steady-state collector (launched in
     * `backgroundTaskScope` from `SyncplayPlayerExtension.onStart`) receives
     * the last file IMMEDIATELY on collection start — covering files that
     * arrived during the switch-gap (old session scope cancelled, new
     * `onStart` hasn't run yet). The controller exposes this as
     * `SyncplayController.inboundFileFlow`.
     */
    private val _inboundFileFlow = MutableSharedFlow<FileData?>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    val inboundFileFlow: SharedFlow<FileData?> get() = _inboundFileFlow

    suspend fun emitInboundFile(file: FileData?) {
        _inboundFileFlow.emit(file)
    }

    /**
     * Outgoing packets queued while disconnected, flushed on reconnection.
     * Guarded by [outboundQueueLock]: the failure path of `transmitPacket` appends from
     * arbitrary IO threads while `onConnected` drains, so the snapshot-then-clear in
     * [drainOutbound] must be atomic against concurrent appends.
     */
    private val outboundQueue = mutableListOf<String>()
    private val outboundQueueLock = Mutex()

    suspend fun queueOutbound(json: String) {
        outboundQueueLock.withLock { outboundQueue.add(json) }
    }

    /** Atomically snapshots and empties the queue. */
    suspend fun drainOutbound(): List<String> = outboundQueueLock.withLock {
        val snapshot = outboundQueue.toList()
        outboundQueue.clear()
        snapshot
    }

    /** Auto-ready on join — the user is ready by default. */
    val ready = MutableStateFlow(true)
}

/**
 * A single chat message in the [Session.messageSequence] log.
 *
 * [username] is the sender's display name; [isSystemMessage] marks server-generated
 * messages (motd, join/leave notices) that have no human sender.
 */
data class ChatMessage(
    val username: String,
    val message: String,
    val isSystemMessage: Boolean = false,
)
