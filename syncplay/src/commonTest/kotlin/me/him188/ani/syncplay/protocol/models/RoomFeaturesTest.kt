package me.him188.ani.syncplay.protocol.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [LenientRoomFeaturesSerializer]: non-object `features` shapes (arrays,
 * primitives, null) decode to default [RoomFeatures] instead of throwing; proper objects
 * decode normally with unknown keys ignored.
 */
class RoomFeaturesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun decode(input: String): RoomFeatures =
        json.decodeFromString(LenientRoomFeaturesSerializer, input)

    @Test
    fun decodes_empty_array_to_defaults() {
        val result = decode("[]")
        assertEquals(RoomFeatures(), result)
    }

    @Test
    fun decodes_empty_object_to_defaults() {
        val result = decode("{}")
        assertEquals(RoomFeatures(), result)
    }

    @Test
    fun decodes_null_to_defaults() {
        val result = decode("null")
        assertEquals(RoomFeatures(), result)
    }

    @Test
    fun decodes_string_primitive_to_defaults() {
        val result = decode("\"some string\"")
        assertEquals(RoomFeatures(), result)
    }

    @Test
    fun decodes_number_primitive_to_defaults() {
        val result = decode("42")
        assertEquals(RoomFeatures(), result)
    }

    @Test
    fun decodes_partial_object_keeping_defaults_for_absent_fields() {
        val result = decode("""{"chat":false,"maxChatMessageLength":200}""")
        assertEquals(
            RoomFeatures(
                supportsChat = false,
                maxChatMessageLength = 200,
            ),
            result,
        )
    }

    @Test
    fun ignores_unknown_object_keys_and_returns_defaults() {
        val result = decode("""{"unknownKey":123}""")
        assertEquals(RoomFeatures(), result)
    }

    @Test
    fun decodes_full_object_round_trip() {
        val original = RoomFeatures(
            isolateRooms = false,
            supportsReadiness = false,
            supportsManagedRooms = false,
            persistentRooms = false,
            supportsChat = false,
            supportsSharedPlaylists = false,
            featureList = false,
            setOthersReadiness = false,
            maxChatMessageLength = 200,
            maxUsernameLength = 20,
            maxRoomNameLength = 40,
            maxFilenameLength = 300,
        )
        val encoded = json.encodeToString(LenientRoomFeaturesSerializer, original)
        val decoded = decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun decodes_via_nullable_wrapper_field_just_like_real_wire_usage() {
        // Mirrors real usage: the serializer is applied to a nullable RoomFeatures field
        // inside a wire DTO (e.g. UserEvent.features). The nullable wrapper handles JSON
        // null; the lenient serializer handles non-object shapes.
        val wrapper = json.decodeFromString(
            FeaturesWrapper.serializer(),
            """{"features":[]}""",
        )
        assertEquals(RoomFeatures(), wrapper.features)
    }

    @Serializable
    private data class FeaturesWrapper(
        @Serializable(with = LenientRoomFeaturesSerializer::class)
        val features: RoomFeatures? = null,
    )
}
