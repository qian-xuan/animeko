package me.him188.ani.syncplay.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.tls.tls
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.syncplay.protocol.models.ConnectionState
import me.him188.ani.syncplay.protocol.models.TlsState
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JVM/Android/Desktop concrete [SyncplayNetworkManager] over `ktor-network` TCP sockets.
 *
 * Inbound: `aSocket` → [Socket.openReadChannel] → [ByteReadChannel.readUTF8Line] →
 * [handlePacket].
 * Outbound: [Socket.openWriteChannel]`(autoFlush = true)` →
 * [ByteWriteChannel.writeStringUtf8].
 * TLS: [tls] extension from `ktor-network-tls` (JVM-only) for STARTTLS upgrade.
 *
 * Inherited by `androidMain` and `desktopMain` via the KMP source set hierarchy — no
 * per-target actual is needed.
 *
 * @param coroutineScope the controller's scope — owns the reader and reconnection loop.
 * @param reconnectInterval delay between reconnection attempts. Defaults to 1 second.
 */
class KtorSyncplayNetworkManager(
    coroutineScope: CoroutineScope,
    reconnectInterval: Duration = 1.seconds,
) : SyncplayNetworkManager(coroutineScope, reconnectInterval) {

    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    private var readerJob: Job? = null

    /**
     * Serializes writes to [writeChannel]. Multiple [sendAsync] calls (bridge controlPlayback,
     * health-monitor playback-broadcast, ACK loop) can race on the same [ByteWriteChannel],
     * corrupting its internal buffer and causing [NullPointerException] inside
     * `writeStringUtf8`. This mutex ensures only one write at a time.
     */
    private val writeMutex = Mutex()

    override suspend fun connectSocket() {
        withContext(Dispatchers.IO) {
            try {
                val connected = aSocket(SelectorManager(Dispatchers.IO))
                    .tcp()
                    .connect(host, port) {
                        socketTimeout = 10000
                    }
                socket = connected
                readChannel = connected.openReadChannel()
                writeChannel = connected.openWriteChannel(autoFlush = true)
                launchReader()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(e.stackTraceToString())
                onConnectionFailed()
            }
        }
    }

    /**
     * Launches the reader coroutine that loops [ByteReadChannel.readUTF8Line] →
     * [handlePacket]. On EOF (null line) or exception, calls [onDisconnected].
     */
    private fun launchReader() {
        val channel = readChannel ?: return
        readerJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    handlePacket(line)
                }
                onDisconnected()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onDisconnected()
            }
        }
    }

    override fun supportsTLS(): Boolean = true

    override fun terminateExistingConnection() {
        runCatching { socket?.close() }
        socket = null
        readChannel = null
        writeChannel = null
        readerJob = null
    }

    override suspend fun writeActualString(s: String) {
        try {
            writeMutex.withLock {
                writeChannel?.writeStringUtf8(s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(e.stackTraceToString())
            onDisconnected()
        }
    }

    override suspend fun upgradeTls() {
        val currentSocket = socket ?: return
        val oldReadChannel = readChannel
        val oldWriteChannel = writeChannel
        readerJob?.cancel()
        try {
            val tlsSocket = currentSocket.tls(coroutineContext) {
                serverName = host
            }
            socket = tlsSocket
            readChannel = tlsSocket.openReadChannel()
            writeChannel = tlsSocket.openWriteChannel(autoFlush = true)
            launchReader()
            tls = TlsState.TLS_YES
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("TLS upgrade failed, staying on plaintext: ${e.stackTraceToString()}")
            socket = currentSocket
            readChannel = oldReadChannel
            writeChannel = oldWriteChannel
            launchReader()
        }
    }

    override fun onConnected() {
        state.value = ConnectionState.CONNECTED
    }

    override fun onDisconnected() {
        reconnect()
    }

    override fun onConnectionFailed() {
        reconnect()
    }

    override fun log(message: String) {
        println(message)
    }
}
