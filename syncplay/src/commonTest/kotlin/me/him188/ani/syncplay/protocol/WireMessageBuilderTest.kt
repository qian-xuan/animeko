package me.him188.ani.syncplay.protocol

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import me.him188.ani.syncplay.protocol.wire.ChatData
import me.him188.ani.syncplay.protocol.wire.ErrorData
import me.him188.ani.syncplay.protocol.wire.FileData
import me.him188.ani.syncplay.protocol.wire.HelloData
import me.him188.ani.syncplay.protocol.wire.Room
import me.him188.ani.syncplay.protocol.wire.SetData
import me.him188.ani.syncplay.protocol.wire.StateData
import me.him188.ani.syncplay.protocol.wire.TLSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests for the [WireMessage] companion builders and [WireMessageHandler] visitor dispatch.
 *
 * Each builder is verified against its exact wire JSON shape — this guards byte-compatibility
 * with the reference Python server, which pattern-matches on key names and payload structure.
 * The null-field-dropping test confirms `explicitNulls = false` on [syncplayJson] omits null
 * fields (not just null defaults). The dispatch test exercises every `on…` method of
 * [WireMessageHandler] to ensure the visitor wiring is correct.
 */
@OptIn(DelicateCoroutinesApi::class)
class WireMessageBuilderTest {

    // ============================================================
    // Builder correctness — one test per builder (10 total)
    // ============================================================

    @Test
    fun chatRequest_produces_bare_string_chat() {
        assertEquals("""{"Chat":"hi"}""", WireMessage.chatRequest("hi").toJson())
    }

    @Test
    fun listRequest_produces_null_list() {
        assertEquals("""{"List":null}""", WireMessage.listRequest().toJson())
    }

    @Test
    fun tlsRequest_produces_send() {
        assertEquals("""{"TLS":{"startTLS":"send"}}""", WireMessage.tlsRequest().toJson())
    }

    @Test
    fun tlsResponse_true_produces_true_string() {
        assertEquals("""{"TLS":{"startTLS":"true"}}""", WireMessage.tlsResponse(true).toJson())
    }

    @Test
    fun tlsResponse_false_produces_false_string() {
        assertEquals("""{"TLS":{"startTLS":"false"}}""", WireMessage.tlsResponse(false).toJson())
    }

    @Test
    fun chatBroadcast_produces_username_message_object() {
        assertEquals(
            """{"Chat":{"username":"alice","message":"hi"}}""",
            WireMessage.chatBroadcast("alice", "hi").toJson()
        )
    }

    @Test
    fun roomChange_produces_set_room_wrapper() {
        assertEquals(
            """{"Set":{"room":{"name":"anime"}}}""",
            WireMessage.roomChange("anime").toJson()
        )
    }

    @Test
    fun error_produces_error_message_wrapper() {
        assertEquals(
            """{"Error":{"message":"bad"}}""",
            WireMessage.error("bad").toJson()
        )
    }

    @Test
    fun file_produces_set_file_wrapper() {
        val file = FileData(name = "ep1.mkv", duration = 24.0, size = "1234")
        assertEquals(
            """{"Set":{"file":{"name":"ep1.mkv","duration":24.0,"size":1234}}}""",
            WireMessage.file(file).toJson()
        )
    }

    @Test
    fun readiness_produces_set_ready_wrapper() {
        assertEquals(
            """{"Set":{"ready":{"username":"alice","isReady":true,"manuallyInitiated":true,"setBy":"alice"}}}""",
            WireMessage.readiness(true, true, "alice", "alice").toJson()
        )
    }

    // ============================================================
    // Null field dropping — explicitNulls=false
    // ============================================================

    @Test
    fun hello_with_null_motd_drops_motd_from_output() {
        val msg = WireMessage.Hello(HelloData(username = "alice", room = Room("anime")))
        val json = msg.toJson()
        assertFalse(
            json.contains("motd"),
            "motd should be dropped under explicitNulls=false, got: $json"
        )
    }

    // ============================================================
    // Handler dispatch — visitor wiring for all 9 variants
    // ============================================================

    @Test
    fun dispatch_invokes_correct_handler_method_for_each_variant() = runBlocking {
        val handler = RecordingHandler()

        WireMessage.Hello(HelloData(username = "alice", room = Room("anime"))).dispatch(handler)
        assertEquals(DispatchedVariant.HELLO, handler.lastCalled)

        WireMessage.State(StateData()).dispatch(handler)
        assertEquals(DispatchedVariant.STATE, handler.lastCalled)

        WireMessage.Set(SetData(room = Room("anime"))).dispatch(handler)
        assertEquals(DispatchedVariant.SET, handler.lastCalled)

        WireMessage.TLS(TLSData(startTLS = "send")).dispatch(handler)
        assertEquals(DispatchedVariant.TLS, handler.lastCalled)

        WireMessage.Error(ErrorData(message = "bad")).dispatch(handler)
        assertEquals(DispatchedVariant.ERROR, handler.lastCalled)

        WireMessage.ListRequest().dispatch(handler)
        assertEquals(DispatchedVariant.LIST_REQUEST, handler.lastCalled)

        WireMessage.ListResponse(emptyMap()).dispatch(handler)
        assertEquals(DispatchedVariant.LIST_RESPONSE, handler.lastCalled)

        WireMessage.ChatRequest("hi").dispatch(handler)
        assertEquals(DispatchedVariant.CHAT_REQUEST, handler.lastCalled)

        WireMessage.ChatBroadcast(ChatData(username = "alice", message = "hi")).dispatch(handler)
        assertEquals(DispatchedVariant.CHAT_BROADCAST, handler.lastCalled)
    }

    private enum class DispatchedVariant {
        HELLO, STATE, SET, TLS, ERROR, LIST_REQUEST, LIST_RESPONSE, CHAT_REQUEST, CHAT_BROADCAST
    }

    private class RecordingHandler : WireMessageHandler {
        var lastCalled: DispatchedVariant? = null

        override suspend fun onHello(message: WireMessage.Hello) { lastCalled = DispatchedVariant.HELLO }
        override suspend fun onState(message: WireMessage.State) { lastCalled = DispatchedVariant.STATE }
        override suspend fun onSet(message: WireMessage.Set) { lastCalled = DispatchedVariant.SET }
        override suspend fun onTLS(message: WireMessage.TLS) { lastCalled = DispatchedVariant.TLS }
        override suspend fun onError(message: WireMessage.Error) { lastCalled = DispatchedVariant.ERROR }
        override suspend fun onListRequest(message: WireMessage.ListRequest) { lastCalled = DispatchedVariant.LIST_REQUEST }
        override suspend fun onListResponse(message: WireMessage.ListResponse) { lastCalled = DispatchedVariant.LIST_RESPONSE }
        override suspend fun onChatRequest(message: WireMessage.ChatRequest) { lastCalled = DispatchedVariant.CHAT_REQUEST }
        override suspend fun onChatBroadcast(message: WireMessage.ChatBroadcast) { lastCalled = DispatchedVariant.CHAT_BROADCAST }
    }
}
