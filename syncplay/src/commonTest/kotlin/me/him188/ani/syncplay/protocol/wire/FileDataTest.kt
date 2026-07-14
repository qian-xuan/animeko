package me.him188.ani.syncplay.protocol.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [FileSizeSerializer]: `size` round-trips as a JSON number when the value parses
 * as a [Long] (raw byte count or the hidden sentinel `0`), and as a JSON string otherwise
 * (12-char privacy hash). Non-primitive shapes are rejected with [SerializationException].
 */
class FileDataTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun round_trips_numeric_size_as_json_number() {
        val original = FileData(size = "220514438")
        val encoded = json.encodeToString(FileData.serializer(), original)
        // Numeric byte count goes out as a JSON number, not a string.
        assertEquals("""{"size":220514438}""", encoded)
        val decoded = json.decodeFromString(FileData.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun round_trips_hash_string_as_json_string() {
        val original = FileData(size = "abc123def456")
        val encoded = json.encodeToString(FileData.serializer(), original)
        assertEquals("""{"size":"abc123def456"}""", encoded)
        val decoded = json.decodeFromString(FileData.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun round_trips_zero_size_as_json_number() {
        val original = FileData(size = "0")
        val encoded = json.encodeToString(FileData.serializer(), original)
        assertEquals("""{"size":0}""", encoded)
        val decoded = json.decodeFromString(FileData.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun rejects_non_primitive_size_with_serialization_exception() {
        val exception = assertFailsWith<SerializationException> {
            json.decodeFromString(FileData.serializer(), """{"size":[]}""")
        }
        assertTrue(
            exception.message?.contains("Expected JSON primitive") == true,
            "Exception message should explain the failure, got: ${exception.message}"
        )
    }
}
