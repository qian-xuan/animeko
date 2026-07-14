package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.WireMessageDeserializer
import me.him188.ani.syncplay.protocol.models.ConnectionState
import me.him188.ani.syncplay.protocol.syncplayJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RoomEventDispatcher] using a [FakeSyncplayNetworkManager] that
 * captures sent messages as typed [WireMessage] objects (decoded from the JSON
 * written via [SyncplayNetworkManager.writeActualString]).
 *
 * - Test 1: controlPlayback(PLAY, tellServer=true) sends State with paused=false
 * - Test 2: controlPlayback(PAUSE, tellServer=true) sends State with paused=true
 * - Test 3: controlPlayback(PLAY, tellServer=false) sends no State
 * - Test 4: sendHello() sends Hello with correct username/room
 * - Test 5: sendMessage("hi") sends ChatRequest with message="hi"
 * - Test 6: sendSeek(5000) sends State with doSeek=true, position=5.0
 */
class RoomEventDispatcherTest {

    @Test
    fun controlPlayback_play_with_tellServer_sends_state_with_paused_false() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val session = Session().apply {
            currentUsername = "user1"
            currentRoom = "room1"
        }
        val protocol = ProtocolManager()
        val dispatcher = RoomEventDispatcher(fake, session, protocol)

        try {
            dispatcher.controlPlayback(Playback.PLAY, tellServer = true)

            assertEquals(1, fake.sentMessages.size)
            val state = assertIs<WireMessage.State>(fake.sentMessages[0])
            val playstate = assertNotNull(state.data.playstate)
            assertEquals(false, playstate.paused)
            assertNull(playstate.doSeek)
            // noteExpectedPlaybackState was called with paused=false before sending
            assertEquals(true, protocol.expectedPlaying)
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun controlPlayback_pause_with_tellServer_sends_state_with_paused_true() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val session = Session().apply {
            currentUsername = "user1"
            currentRoom = "room1"
        }
        val protocol = ProtocolManager()
        val dispatcher = RoomEventDispatcher(fake, session, protocol)

        try {
            dispatcher.controlPlayback(Playback.PAUSE, tellServer = true)

            assertEquals(1, fake.sentMessages.size)
            val state = assertIs<WireMessage.State>(fake.sentMessages[0])
            val playstate = assertNotNull(state.data.playstate)
            assertEquals(true, playstate.paused)
            assertNull(playstate.doSeek)
            // noteExpectedPlaybackState was called with paused=true before sending
            assertEquals(false, protocol.expectedPlaying)
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun controlPlayback_play_with_tellServer_false_sends_no_state() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val session = Session().apply {
            currentUsername = "user1"
            currentRoom = "room1"
        }
        val protocol = ProtocolManager()
        val dispatcher = RoomEventDispatcher(fake, session, protocol)

        try {
            dispatcher.controlPlayback(Playback.PLAY, tellServer = false)

            assertTrue(fake.sentMessages.isEmpty())
            // noteExpectedPlaybackState was still called even without telling the server
            assertEquals(true, protocol.expectedPlaying)
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun sendHello_sends_hello_with_correct_username_and_room() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val session = Session().apply {
            currentUsername = "user1"
            currentRoom = "room1"
        }
        val protocol = ProtocolManager()
        val dispatcher = RoomEventDispatcher(fake, session, protocol)

        try {
            dispatcher.sendHello()

            assertEquals(1, fake.sentMessages.size)
            val hello = assertIs<WireMessage.Hello>(fake.sentMessages[0])
            assertEquals("user1", hello.data.username)
            assertEquals("room1", hello.data.room?.name)
            assertEquals(ProtocolManager.SYNCPLAY_LEGACY_VERSION, hello.data.version)
            assertEquals(ProtocolManager.SYNCPLAY_PROTOCOL_VERSION, hello.data.realversion)
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun sendMessage_sends_chatRequest_with_message() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val session = Session().apply {
            currentUsername = "user1"
            currentRoom = "room1"
        }
        val protocol = ProtocolManager()
        val dispatcher = RoomEventDispatcher(fake, session, protocol)

        try {
            dispatcher.sendMessage("hi")

            assertEquals(1, fake.sentMessages.size)
            val chat = assertIs<WireMessage.ChatRequest>(fake.sentMessages[0])
            assertEquals("hi", chat.message)
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun sendSeek_sends_state_with_doSeek_true_and_correct_position() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val session = Session().apply {
            currentUsername = "user1"
            currentRoom = "room1"
        }
        val protocol = ProtocolManager()
        val dispatcher = RoomEventDispatcher(fake, session, protocol)

        try {
            dispatcher.sendSeek(5000)

            assertEquals(1, fake.sentMessages.size)
            val state = assertIs<WireMessage.State>(fake.sentMessages[0])
            val playstate = assertNotNull(state.data.playstate)
            assertEquals(true, playstate.doSeek)
            val position = assertNotNull(playstate.position)
            assertEquals(5.0, position, 0.001)
        } finally {
            fake.invalidate()
        }
    }

    // -- Test fixture --

    /**
     * Fake [SyncplayNetworkManager] that captures outbound messages as typed
     * [WireMessage] objects by decoding the JSON passed to [writeActualString].
     *
     * Follows the same pattern as `FakeNetworkManager` in `SyncplayControllerTest`,
     * but decodes the captured JSON back to typed messages for field-level assertions
     * instead of matching on raw string contents.
     */
    private class FakeSyncplayNetworkManager(
        coroutineScope: CoroutineScope,
    ) : SyncplayNetworkManager(coroutineScope) {
        val sentMessages = mutableListOf<WireMessage>()

        override suspend fun connectSocket() {}

        override fun supportsTLS(): Boolean = false

        override fun terminateExistingConnection() {}

        override suspend fun writeActualString(s: String) {
            val json = s.trimEnd('\r', '\n')
            sentMessages.add(syncplayJson.decodeFromString(WireMessageDeserializer, json))
        }

        override suspend fun upgradeTls() {}

        override fun onConnected() {
            state.value = ConnectionState.CONNECTED
        }

        override fun onDisconnected() {}

        override fun onConnectionFailed() {}
    }
}
