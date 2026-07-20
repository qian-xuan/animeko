package me.him188.ani.syncplay.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.trace
import me.him188.ani.utils.logging.warn
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.WireMessageDeserializer
import me.him188.ani.syncplay.protocol.WireMessageHandler
import me.him188.ani.syncplay.protocol.models.ConnectionState
import me.him188.ani.syncplay.protocol.models.TlsState
import me.him188.ani.syncplay.protocol.syncplayJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client-side TCP network layer for the Syncplay protocol.
 *
 * Inbound: raw lines → [syncplayJson] decode via [WireMessageDeserializer] → typed
 * [WireMessage] → [WireMessage.dispatch] into [handler].
 *
 * Outbound: callers construct typed [WireMessage] instances and pass them to [send] /
 * [sendAsync]; encoding goes through [syncplayJson] and onto the wire.
 *
 * This is a self-contained abstract base with NO viewmodel dependency. The concrete
 * subclass (T2.2) implements the socket transport; the controller (T2.3) wires [handler],
 * [onReadyForHandshake], [queueOutbound], and [resetSyncAnchorForReconnect] via lambda
 * properties.
 *
 * @param coroutineScope the controller's scope — owns the inbound consumer and
 *   reconnection loop.
 * @param reconnectInterval delay between reconnection attempts. Defaults to 1 second.
 */
abstract class SyncplayNetworkManager(
    protected val coroutineScope: CoroutineScope,
    private val reconnectInterval: Duration = 1.seconds,
) {
    /** Current connection state, observable via [StateFlow]. */
    val state: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** TLS_NO = plain TCP, TLS_YES = encrypted, TLS_ASK = negotiate with server. */
    var tls: TlsState = TlsState.TLS_NO

    /** Server hostname, set by the controller before calling [connect]. */
    var host: String = ""

    /** Server port, set by the controller before calling [connect]. */
    var port: Int = 0

    /**
     * The server message handler. Set by the controller (Wave 3) to the room's handler.
     * Defaults to a no-op implementation — messages are silently dropped until set.
     */
    var handler: WireMessageHandler = object : WireMessageHandler {}

    // -- Abstract methods: concrete subclass (T2.2) implements socket transport --

    /** Opens the TCP socket and launches a reader coroutine feeding each line to [handlePacket]. */
    abstract suspend fun connectSocket()

    abstract fun supportsTLS(): Boolean

    abstract fun terminateExistingConnection()

    abstract suspend fun writeActualString(s: String)

    /**
     * Inserts the TLS handler into the channel pipeline AND awaits handshake completion
     * before returning. The await is critical: callers send `Hello` immediately after this
     * returns, and if the handshake hasn't completed the Hello gets framed as a TLS alert.
     */
    abstract suspend fun upgradeTls()

    /** Called when connection succeeds — sets state=CONNECTED. */
    abstract fun onConnected()

    /** Called when the connection drops — triggers [reconnect]. */
    abstract fun onDisconnected()

    /** Called when a connection attempt fails. */
    abstract fun onConnectionFailed()

    // -- Lambda hooks: the controller (T2.3) / engine (Wave 3) sets these --

    /**
     * Called after a successful non-TLS connect, or after TLS handshake completes.
     * The controller sets this to call `sendHello()`. No-op default.
     */
    var onReadyForHandshake: suspend () -> Unit = {}

    /** Queues a packet for replay on reconnect. No-op default — the Session (T2.3) sets this. */
    var queueOutbound: suspend (String) -> Unit = {}

    /** Drops the stale sync anchor so the first State on a new socket re-anchors. No-op default. */
    var resetSyncAnchorForReconnect: () -> Unit = {}

    // -- Concrete methods --

    /**
     * Connects to the server. If [tls] is TLS_ASK, sends a TLS negotiation packet first;
     * otherwise calls [onReadyForHandshake] (which sends Hello).
     */
    open suspend fun connect() {
        logger.info { "Connecting to $host:$port (tls=$tls)" }
        terminateExistingConnection()
        state.value = ConnectionState.CONNECTING
        try {
            connectSocket()
            if (tls == TlsState.TLS_ASK) {
                send(WireMessage.tlsRequest())
            } else {
                onReadyForHandshake.invoke()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Connection failed to $host:$port" }
            onConnectionFailed()
        }
    }

    private var reconnectionJob: Job? = null

    /**
     * Schedules automatic reconnection. A single coroutine owns the whole retry loop and keeps
     * retrying until the state reaches CONNECTED or the job is cancelled by [invalidate] /
     * [terminateExistingConnection].
     *
     * The guard is on [Job.isActive], not isCompleted: a synchronous connect failure re-enters
     * [reconnect] from within the running loop, where the job is still active, so the re-entry
     * is a harmless no-op and the existing loop keeps driving retries.
     */
    fun reconnect() {
        if (reconnectionJob?.isActive == true) return
        logger.info { "Scheduling reconnect to $host:$port (interval=${reconnectInterval.inWholeMilliseconds}ms)" }
        reconnectionJob = coroutineScope.launch(Dispatchers.IO) {
            resetSyncAnchorForReconnect.invoke()
            while (isActive && state.value != ConnectionState.CONNECTED) {
                state.value = ConnectionState.SCHEDULING_RECONNECT
                delay(reconnectInterval)
                if (!isActive || state.value == ConnectionState.CONNECTED) break
                // Re-arm TLS negotiation for the fresh socket. After a successful encrypted
                // session [tls] is left at TLS_YES, but a brand-new socket has no SSL handler
                // in its pipeline — resetting to TLS_ASK makes the reconnect re-do the same
                // negotiation the initial connect did.
                if (tls == TlsState.TLS_YES) tls = TlsState.TLS_ASK
                logger.debug { "Reconnect attempt: state=${state.value}" }
                connect()
            }
        }
    }

    /**
     * Inbound lines, processed STRICTLY one at a time in arrival order by the single consumer
     * below. The Syncplay protocol is serial — handling two `State`s concurrently would
     * interleave their mutations. A channel plus single consumer also guarantees a handler
     * that suspends mid-message finishes the whole message before the next line is read.
     */
    private val inboundLines = Channel<String>(capacity = Channel.UNLIMITED)

    init {
        coroutineScope.launch(Dispatchers.Default) {
            for (line in inboundLines) processPacket(line)
        }
    }

    /**
     * Enqueues a raw inbound line for ordered processing. Called from raw transport
     * threads — must not block.
     */
    fun handlePacket(jsonString: String) {
        inboundLines.trySend(jsonString)
    }

    /**
     * Decodes a raw inbound line and dispatches the typed [WireMessage] to [handler].
     *
     * Catches ONLY [SerializationException] (skip the poisoned line) and
     * [CancellationException] (rethrow). Any other exception propagates — a handler
     * bug should be visible, not silently swallowed.
     */
    private suspend fun processPacket(jsonString: String) {
        logger.trace { "SERVER>>> $jsonString" }
        try {
            val message = syncplayJson.decodeFromString(WireMessageDeserializer, jsonString)
            message.dispatch(handler)
        } catch (e: SerializationException) {
            logger.warn(e) { "Skipping unparseable server message: $jsonString" }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun onError() {
        onDisconnected()
    }

    /**
     * Encodes a [WireMessage] to JSON and writes it. Uses [WireMessage.toJson] so the
     * concrete-subclass serializer is always used, avoiding the polymorphic-discriminator
     * trap.
     *
     * Hello and State are NOT queueable: Hello re-runs on reconnect, and State carries a
     * position that was true the instant the socket died but is stale by reconnect.
     */
    suspend fun send(message: WireMessage) {
        val queueable = message !is WireMessage.Hello && message !is WireMessage.State
        transmitPacket(message.toJson(), queueable = queueable)
    }

    /** Fire-and-forget [send] — launched on [Dispatchers.IO]. */
    fun sendAsync(message: WireMessage) {
        coroutineScope.launch(Dispatchers.IO) { send(message) }
    }

    /**
     * Appends CRLF, writes to socket with a 10s timeout. Retries up to 3 times before
     * giving up. On final failure, packets flagged [queueable] get queued via
     * [queueOutbound] for replay on reconnect. Hello and State are NOT queueable.
     */
    suspend fun transmitPacket(json: String, queueable: Boolean = true, retryCounter: Int = 0) {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(10.seconds) {
                    val finalOut = json + "\r\n"
                    logger.trace { "CLIENT>>> $finalOut" }
                    writeActualString(finalOut)
                }
            } catch (e: Exception) {
                logger.warn(e) { "Write failed for: $json" }
                if (retryCounter >= 3) {
                    logger.warn { "Socket invalid after 3 retries, marking disconnected" }
                    if (queueable) {
                        queueOutbound.invoke(json)
                    }
                    onError()
                } else {
                    transmitPacket(json, queueable, retryCounter = retryCounter + 1)
                }
            }
        }
    }

    /** Tears down the connection and resets state. */
    open fun invalidate() {
        logger.info { "Invalidating connection to $host:$port" }
        reconnectionJob?.cancel()
        terminateExistingConnection()
        inboundLines.close()
        state.value = ConnectionState.DISCONNECTED
        tls = TlsState.TLS_NO
    }

    companion object {
        private val logger = logger<SyncplayNetworkManager>()
    }
}
