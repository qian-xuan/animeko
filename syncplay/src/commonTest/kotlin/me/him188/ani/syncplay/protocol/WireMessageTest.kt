package me.him188.ani.syncplay.protocol

import kotlinx.serialization.SerializationException
import me.him188.ani.syncplay.protocol.wire.ChatData
import me.him188.ani.syncplay.protocol.wire.ErrorData
import me.him188.ani.syncplay.protocol.wire.HelloData
import me.him188.ani.syncplay.protocol.wire.ListUserData
import me.him188.ani.syncplay.protocol.wire.Room
import me.him188.ani.syncplay.protocol.wire.SetData
import me.him188.ani.syncplay.protocol.wire.StateData
import me.him188.ani.syncplay.protocol.wire.TLSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-trip and routing tests for [WireMessage] and [WireMessageDeserializer].
 *
 * Each of the 9 variants round-trips through `toJson()` →
 * `decodeFromString(WireMessageDeserializer, json)` with equality preserved. The
 * deserializer's payload-shape routing for the asymmetric `Chat` and `List` keys is
 * verified separately, as are the failure cases (non-object input, unknown top-level key).
 *
 * Note: decoding uses the explicit `WireMessageDeserializer` (a
 * `JsonContentPolymorphicSerializer`) rather than `decodeFromString<WireMessage>` because
 * the sealed interface is annotated with plain `@Serializable` (no `with = …`), so the
 * reified call would resolve to the generated `SealedSerializer` which expects a `"type"`
 * discriminator the Syncplay wire format does not carry.
 */
class WireMessageTest {

    /** Encode via [WireMessage.toJson] (concrete-subclass serializer, no `type` discriminator)
     *  then decode via the explicit [WireMessageDeserializer]. */
    private fun roundTrip(message: WireMessage): WireMessage {
        val json = message.toJson()
        return syncplayJson.decodeFromString(WireMessageDeserializer, json)
    }

    // ============================================================
    // Happy path — one round-trip per variant (9 total)
    // ============================================================

    @Test
    fun round_trips_Hello() {
        val original = WireMessage.Hello(
            HelloData(username = "alice", room = Room("anime"), version = "1.7.3")
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_State() {
        val original = WireMessage.State(StateData())
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_Set() {
        val original = WireMessage.Set(SetData(room = Room("anime")))
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_TLS() {
        val original = WireMessage.TLS(TLSData(startTLS = "send"))
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_Error() {
        val original = WireMessage.Error(ErrorData(message = "bad"))
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_ListRequest() {
        val original = WireMessage.ListRequest()
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_ListResponse() {
        val original = WireMessage.ListResponse(
            mapOf("anime" to mapOf("alice" to ListUserData(position = 42.0, isReady = true)))
        )
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_ChatRequest() {
        val original = WireMessage.ChatRequest("hello")
        assertEquals(original, roundTrip(original))
    }

    @Test
    fun round_trips_ChatBroadcast() {
        val original = WireMessage.ChatBroadcast(ChatData(username = "alice", message = "hi"))
        assertEquals(original, roundTrip(original))
    }

    // ============================================================
    // Deserializer routing — Chat and List payload-shape dispatch
    // ============================================================

    @Test
    fun routes_Chat_string_payload_to_ChatRequest() {
        val decoded = syncplayJson.decodeFromString(WireMessageDeserializer, """{"Chat":"hello"}""")
        assertTrue(decoded is WireMessage.ChatRequest, "expected ChatRequest, got ${decoded::class.simpleName}")
        assertEquals("hello", decoded.message)
    }

    @Test
    fun routes_Chat_object_payload_to_ChatBroadcast() {
        val decoded = syncplayJson.decodeFromString(
            WireMessageDeserializer, """{"Chat":{"username":"alice","message":"hi"}}"""
        )
        assertTrue(decoded is WireMessage.ChatBroadcast, "expected ChatBroadcast, got ${decoded::class.simpleName}")
        assertEquals("alice", decoded.data.username)
        assertEquals("hi", decoded.data.message)
    }

    @Test
    fun routes_List_object_payload_to_ListResponse() {
        val decoded = syncplayJson.decodeFromString(WireMessageDeserializer, """{"List":{}}""")
        assertTrue(decoded is WireMessage.ListResponse, "expected ListResponse, got ${decoded::class.simpleName}")
        assertEquals(emptyMap(), decoded.rooms)
    }

    @Test
    fun routes_List_null_payload_to_ListRequest() {
        val decoded = syncplayJson.decodeFromString(WireMessageDeserializer, """{"List":null}""")
        assertTrue(decoded is WireMessage.ListRequest, "expected ListRequest, got ${decoded::class.simpleName}")
    }

    // ============================================================
    // Failure cases — non-object input and unknown top-level key
    // ============================================================

    @Test
    fun rejects_string_input_with_serialization_exception() {
        assertFailsWith<SerializationException> {
            syncplayJson.decodeFromString(WireMessageDeserializer, "\"hello\"")
        }
    }

    @Test
    fun rejects_array_input_with_serialization_exception() {
        assertFailsWith<SerializationException> {
            syncplayJson.decodeFromString(WireMessageDeserializer, "[]")
        }
    }

    @Test
    fun rejects_number_input_with_serialization_exception() {
        assertFailsWith<SerializationException> {
            syncplayJson.decodeFromString(WireMessageDeserializer, "42")
        }
    }

    @Test
    fun rejects_unknown_top_level_key_with_serialization_exception() {
        assertFailsWith<SerializationException> {
            syncplayJson.decodeFromString(WireMessageDeserializer, """{"Unknown":{}}""")
        }
    }
}
