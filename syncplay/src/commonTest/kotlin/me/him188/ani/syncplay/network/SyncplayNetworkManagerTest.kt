package me.him188.ani.syncplay.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.WireMessageHandler
import me.him188.ani.syncplay.protocol.models.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [SyncplayNetworkManager] using a [FakeNetworkManager] subclass.
 *
 * - Test 1 (happy): two inbound lines dispatched in order to the handler.
 * - Test 2 (poisoned line): malformed JSON is skipped, valid line still processed.
 * - Test 3 (transmitPacket): CRLF is appended to the raw write.
 * - Test 4 (send): [WireMessage] is JSON-encoded then CRLF-appended.
 */
@OptIn(DelicateCoroutinesApi::class)
class SyncplayNetworkManagerTest {

    @Test
    fun happy_path_two_lines_dispatched_in_order() = runBlocking {
        val manager = FakeNetworkManager(this)
        try {
            val handler = RecordingHandler()
            manager.handler = handler

            manager.handlePacket("""{"Chat":"first"}""")
            manager.handlePacket("""{"Chat":"second"}""")

            val first = withTimeout(5.seconds) { handler.received.receive() }
            val second = withTimeout(5.seconds) { handler.received.receive() }

            assertEquals("first", (first as WireMessage.ChatRequest).message)
            assertEquals("second", (second as WireMessage.ChatRequest).message)
        } finally {
            manager.invalidate()
        }
    }

    @Test
    fun poisoned_line_skipped_valid_line_still_processed() = runBlocking {
        val manager = FakeNetworkManager(this)
        try {
            val handler = RecordingHandler()
            manager.handler = handler

            manager.handlePacket("not valid json")
            manager.handlePacket("""{"Chat":"valid"}""")

            val message = withTimeout(5.seconds) { handler.received.receive() }

            assertEquals("valid", (message as WireMessage.ChatRequest).message)
        } finally {
            manager.invalidate()
        }
    }

    @Test
    fun transmitPacket_appends_crlf() = runBlocking {
        val manager = FakeNetworkManager(this)
        try {
            manager.transmitPacket("{}", queueable = false)
            assertEquals(listOf("{}\r\n"), manager.writes)
        } finally {
            manager.invalidate()
        }
    }

    @Test
    fun send_encodes_message_and_appends_crlf() = runBlocking {
        val manager = FakeNetworkManager(this)
        try {
            manager.send(WireMessage.chatRequest("hi"))
            assertEquals(listOf("""{"Chat":"hi"}""" + "\r\n"), manager.writes)
        } finally {
            manager.invalidate()
        }
    }

    // -- Test fixtures --

    private class FakeNetworkManager(
        coroutineScope: CoroutineScope,
    ) : SyncplayNetworkManager(coroutineScope) {
        val writes = mutableListOf<String>()
        var connectSocketCalled = false
        var terminateCalled = false
        var onConnectedCalled = false
        var onDisconnectedCalled = false
        var onConnectionFailedCalled = false

        override suspend fun connectSocket() {
            connectSocketCalled = true
        }

        override fun supportsTLS(): Boolean = false

        override fun terminateExistingConnection() {
            terminateCalled = true
        }

        override suspend fun writeActualString(s: String) {
            writes.add(s)
        }

        override suspend fun upgradeTls() {}

        override fun onConnected() {
            onConnectedCalled = true
            state.value = ConnectionState.CONNECTED
        }

        override fun onDisconnected() {
            onDisconnectedCalled = true
        }

        override fun onConnectionFailed() {
            onConnectionFailedCalled = true
        }
    }

    private class RecordingHandler : WireMessageHandler {
        val received = Channel<WireMessage>(Channel.UNLIMITED)

        override suspend fun onHello(message: WireMessage.Hello) { received.trySend(message) }
        override suspend fun onState(message: WireMessage.State) { received.trySend(message) }
        override suspend fun onSet(message: WireMessage.Set) { received.trySend(message) }
        override suspend fun onTLS(message: WireMessage.TLS) { received.trySend(message) }
        override suspend fun onError(message: WireMessage.Error) { received.trySend(message) }
        override suspend fun onListRequest(message: WireMessage.ListRequest) { received.trySend(message) }
        override suspend fun onListResponse(message: WireMessage.ListResponse) { received.trySend(message) }
        override suspend fun onChatRequest(message: WireMessage.ChatRequest) { received.trySend(message) }
        override suspend fun onChatBroadcast(message: WireMessage.ChatBroadcast) { received.trySend(message) }
    }
}
