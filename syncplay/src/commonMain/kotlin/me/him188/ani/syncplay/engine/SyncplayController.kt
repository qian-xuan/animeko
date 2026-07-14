package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.models.ConnectionState
import me.him188.ani.syncplay.protocol.models.TlsState
import me.him188.ani.syncplay.protocol.models.User
import me.him188.ani.syncplay.protocol.wire.FileData

/**
 * Owns the full Syncplay client stack: a [SyncplayNetworkManager] transport, a [Session]
 * for connection state, a [ProtocolManager] for the room's global playback state, a
 * [RoomEventDispatcher] for outbound protocol messages, a [SyncplayMessageHandler] for
 * inbound reactions, a [ChannelHealthMonitor] for keepalive/watchdog, and implements
 * [RoomCallback] to bridge inbound room events to the player (T4.3 scope).
 *
 * The controller is injected with a concrete [SyncplayNetworkManager] subclass (e.g.
 * [me.him188.ani.syncplay.network.KtorSyncplayNetworkManager]) — the Koin binding lives
 * in `CommonKoinModule.kt` and uses [createSyncplayNetworkManager] to construct the
 * platform-specific instance.
 *
 * Player-driving callbacks ([RoomCallback.onSomeonePaused], [onSomeonePlayed], etc.)
 * are no-ops here — the player bridge (T4.2/T4.3) will override them to drive the
 * engine. [onConnected] / [onDisconnected] start/stop the [ChannelHealthMonitor].
 *
 * @param networkManager the concrete transport (injected — Koin in T4.1).
 * @param controllerScope owns long-lived coroutines (inbound consumer, reconnect loop,
 *   health-monitor coroutines).
 */
class SyncplayController(
    private val networkManager: SyncplayNetworkManager,
    private val controllerScope: CoroutineScope,
) : RoomCallback {
    val session = Session()

    /** Room-level global playback state, anti-feedback counters, and State-packet builder. */
    val protocol = ProtocolManager()

    /**
     * Outbound protocol messages (Hello, seek, chat, playback control). Lazy because it
     * only depends on [networkManager], [session], and [protocol] — all available at
     * construction — but is not needed until [onReadyForHandshake] fires.
     */
    val dispatcher: RoomEventDispatcher by lazy {
        RoomEventDispatcher(networkManager, session, protocol)
    }

    /**
     * The engine's play-state input — the player bridge (T4.3) writes to this flow, the
     * [ChannelHealthMonitor]'s playback-broadcast coroutine reads it. Exposed so the
     * bridge can call `isPlayingFlow.value = player.isPlaying`.
     */
    val isPlayingFlow = MutableStateFlow(false)

    /**
     * Inbound file-change events from other peers. The player bridge (T4.3)
     * collects this to parse identity-in-filename and auto-switch episodes.
     * `replay = 1` so the bridge gets the last file immediately on collection
     * start (covers the switch-gap).
     */
    val inboundFileFlow: SharedFlow<FileData?> get() = session.inboundFileFlow

    /**
     * Player bridge delegate — the [SyncplayPlayerExtension] (T4.3) sets this
     * in `onStart` so inbound room events (pause/play/seek) drive the player.
     * `null` when no bridge is attached (controller not connected to a player).
     */
    @Volatile
    var playerBridge: RoomCallback? = null

    /**
     * Channel-health monitoring (list-probe, state watchdog, playback-broadcast). Lazy
     * because it is only needed while CONNECTED — [onConnected] starts it, [onDisconnected]
     * stops it.
     */
    val healthMonitor: ChannelHealthMonitor by lazy {
        ChannelHealthMonitor(controllerScope, networkManager, protocol, isPlayingFlow)
    }

    // -- Exposed flows --

    /** Connection state, delegated to the network manager's [StateFlow]. */
    val state: StateFlow<ConnectionState> get() = networkManager.state

    /** Room user list, delegated to the session's [StateFlow]. */
    val userList: StateFlow<List<User>> get() = session.userList

    /** Chat + system message log, delegated to the session's [StateFlow]. */
    val messageSequence: StateFlow<List<ChatMessage>> get() = session.messageSequence

    // -- Handler + hook wiring --

    init {
        // Replace the T2.3 inline handler with the full SyncplayMessageHandler (T3.4).
        // The handler routes inbound messages to session/protocol/callback/networkManager.
        networkManager.handler = SyncplayMessageHandler(session, protocol, this, networkManager)

        // Delegate the Hello handshake to the dispatcher (canonical outbound path).
        networkManager.onReadyForHandshake = { dispatcher.sendHello() }

        // Queue outbound packets for replay on reconnect.
        networkManager.queueOutbound = { json -> session.queueOutbound(json) }

        // Drop the stale sync anchor so the first State on a new socket re-anchors.
        networkManager.resetSyncAnchorForReconnect = { protocol.lastGlobalUpdate = null }
    }

    // -- Public API --

    /**
     * Connects to the server, which triggers the Hello handshake via [onReadyForHandshake].
     *
     * @param enableTLS if true, sets [TlsState.TLS_ASK] to negotiate STARTTLS with the
     *   server; if false, uses plain TCP and sends Hello immediately.
     */
    suspend fun connect(
        host: String,
        port: Int,
        room: String,
        username: String,
        password: String,
        enableTLS: Boolean,
    ) {
        session.serverHost = host
        session.serverPort = port
        session.currentRoom = room
        session.currentUsername = username
        session.currentPassword = password
        networkManager.host = host
        networkManager.port = port
        networkManager.tls = if (enableTLS) TlsState.TLS_ASK else TlsState.TLS_NO
        networkManager.connect()
    }

    /**
     * Tears down the connection, stops the health monitor, and resets state.
     */
    fun disconnect() {
        healthMonitor.stop()
        networkManager.invalidate()
    }

    /**
     * Sends a `Set.file` message to announce this client's current file to the
     * room. Used by the player bridge (T4.3) on media load to encode
     * identity-in-filename (`subjectId[episodeId]`) for animeko peers.
     */
    suspend fun sendFile(file: FileData) {
        networkManager.send(WireMessage.file(file))
    }

    // -- RoomCallback: connection lifecycle --

    override suspend fun onConnected() {
        healthMonitor.start()
    }

    override suspend fun onDisconnected() {
        healthMonitor.stop()
    }

    // -- RoomCallback: player-driving callbacks (delegated to playerBridge) --

    override suspend fun onSomeonePaused(setBy: String) {
        playerBridge?.onSomeonePaused(setBy)
    }

    override suspend fun onSomeonePlayed(setBy: String) {
        playerBridge?.onSomeonePlayed(setBy)
    }

    override suspend fun onSomeoneSeeked(setBy: String, positionSec: Double) {
        playerBridge?.onSomeoneSeeked(setBy, positionSec)
    }

    override suspend fun onSomeoneBehind(setBy: String, positionSec: Double) {
        playerBridge?.onSomeoneBehind(setBy, positionSec)
    }

    override suspend fun onSomeoneFastForwarded(setBy: String, positionSec: Double) {
        playerBridge?.onSomeoneFastForwarded(setBy, positionSec)
    }
}
