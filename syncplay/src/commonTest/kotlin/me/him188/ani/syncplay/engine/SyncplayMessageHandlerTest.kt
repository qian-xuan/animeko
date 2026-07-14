package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.models.ConnectionState
import me.him188.ani.syncplay.protocol.models.RoomFeatures
import me.him188.ani.syncplay.protocol.wire.HelloData
import me.him188.ani.syncplay.protocol.wire.SetData
import me.him188.ani.syncplay.protocol.wire.UserEvent
import me.him188.ani.syncplay.protocol.wire.UserSetData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [SyncplayMessageHandler] using a [FakeSyncplayNetworkManager] that
 * captures outbound messages and a [RecordingCallback] that captures callback
 * invocations.
 *
 * - Test 1: onSet user joined → session.userList contains the user
 * - Test 2: onSet user left → user removed from session.userList
 * - Test 3: onSet ready → user's readiness updated
 * - Test 4: onChatBroadcast → callback.onChatReceived called + messageSequence updated
 * - Test 5: onHello → session.roomFeatures set + callback.onConnected called
 * - Test 6: onError → error message in session.messageSequence
 */
class SyncplayMessageHandlerTest {

    @Test
    fun onSet_user_joined_adds_user_to_list() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val callback = RecordingCallback()
        val session = Session().apply { currentUsername = "me"; currentRoom = "room1" }
        val protocol = ProtocolManager()
        val handler = SyncplayMessageHandler(session, protocol, callback, fake)

        try {
            handler.onSet(
                WireMessage.Set(
                    SetData(
                        user = mapOf(
                            "bob" to UserSetData(event = UserEvent(joined = JsonPrimitive(true)))
                        )
                    )
                )
            )

            val users = session.userList.value
            assertEquals(1, users.size)
            assertEquals("bob", users[0].name)
            assertTrue(callback.joined.contains("bob"), "Expected onSomeoneJoined('bob'), got: ${callback.joined}")
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun onSet_user_left_removes_user_from_list() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val callback = RecordingCallback()
        val session = Session().apply { currentUsername = "me"; currentRoom = "room1" }
        val protocol = ProtocolManager()
        val handler = SyncplayMessageHandler(session, protocol, callback, fake)

        try {
            handler.onSet(
                WireMessage.Set(
                    SetData(
                        user = mapOf(
                            "bob" to UserSetData(event = UserEvent(joined = JsonPrimitive(true)))
                        )
                    )
                )
            )
            assertEquals(1, session.userList.value.size)

            handler.onSet(
                WireMessage.Set(
                    SetData(
                        user = mapOf(
                            "bob" to UserSetData(event = UserEvent(left = JsonPrimitive(true)))
                        )
                    )
                )
            )

            assertEquals(0, session.userList.value.size)
            assertTrue(callback.left.contains("bob"), "Expected onSomeoneLeft('bob'), got: ${callback.left}")
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun onSet_ready_updates_user_readiness() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val callback = RecordingCallback()
        val session = Session().apply { currentUsername = "me"; currentRoom = "room1" }
        val protocol = ProtocolManager()
        val handler = SyncplayMessageHandler(session, protocol, callback, fake)

        try {
            handler.onSet(
                WireMessage.Set(
                    SetData(
                        user = mapOf(
                            "bob" to UserSetData(event = UserEvent(joined = JsonPrimitive(true)))
                        )
                    )
                )
            )
            assertEquals(false, session.userList.value[0].readiness)

            handler.onSet(
                WireMessage.Set(
                    SetData(
                        ready = me.him188.ani.syncplay.protocol.wire.ReadyData(
                            username = "bob",
                            isReady = true,
                        )
                    )
                )
            )

            assertEquals(true, session.userList.value[0].readiness)
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun onChatBroadcast_calls_callback_and_appends_to_messageSequence() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val callback = RecordingCallback()
        val session = Session().apply { currentUsername = "me"; currentRoom = "room1" }
        val protocol = ProtocolManager()
        val handler = SyncplayMessageHandler(session, protocol, callback, fake)

        try {
            handler.onChatBroadcast(WireMessage.chatBroadcast("alice", "hello world"))

            assertEquals(1, callback.chats.size)
            assertEquals("alice", callback.chats[0].first)
            assertEquals("hello world", callback.chats[0].second)

            val messages = session.messageSequence.value
            assertEquals(1, messages.size)
            assertEquals("alice", messages[0].username)
            assertEquals("hello world", messages[0].message)
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun onHello_sets_roomFeatures_and_calls_onConnected() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val callback = RecordingCallback()
        val session = Session().apply { currentUsername = "me"; currentRoom = "room1" }
        val protocol = ProtocolManager()
        val handler = SyncplayMessageHandler(session, protocol, callback, fake)

        try {
            val features = RoomFeatures(supportsChat = false, maxChatMessageLength = 200)
            handler.onHello(
                WireMessage.Hello(
                    HelloData(
                        motd = "welcome",
                        features = features,
                    )
                )
            )

            assertEquals(features, session.roomFeatures)
            assertEquals(1, callback.connectedCalls)
            assertTrue(fake.onConnectedCalled, "networkManager.onConnected() should be called")
        } finally {
            fake.invalidate()
        }
    }

    @Test
    fun onError_appends_error_to_messageSequence() = runBlocking {
        val fake = FakeSyncplayNetworkManager(this)
        val callback = RecordingCallback()
        val session = Session().apply { currentUsername = "me"; currentRoom = "room1" }
        val protocol = ProtocolManager()
        val handler = SyncplayMessageHandler(session, protocol, callback, fake)

        try {
            handler.onError(WireMessage.error("fail"))

            val messages = session.messageSequence.value
            assertEquals(1, messages.size)
            assertEquals("fail", messages[0].message)
            assertTrue(messages[0].isSystemMessage, "Error message should be a system message")
        } finally {
            fake.invalidate()
        }
    }

    // -- Test fixtures --

    /**
     * Captures all [RoomCallback] invocations for assertion.
     */
    private class RecordingCallback : RoomCallback {
        val joined = mutableListOf<String>()
        val left = mutableListOf<String>()
        val chats = mutableListOf<Pair<String, String>>()
        var connectedCalls = 0
        val seeked = mutableListOf<Pair<String, Double>>()
        val behind = mutableListOf<Pair<String, Double>>()
        val fastForwarded = mutableListOf<Pair<String, Double>>()
        val paused = mutableListOf<String>()
        val played = mutableListOf<String>()

        override suspend fun onSomeoneJoined(username: String) { joined.add(username) }
        override suspend fun onSomeoneLeft(username: String) { left.add(username) }
        override suspend fun onChatReceived(username: String, message: String) {
            chats.add(username to message)
        }
        override suspend fun onConnected() { connectedCalls++ }
        override suspend fun onSomeoneSeeked(setBy: String, positionSec: Double) {
            seeked.add(setBy to positionSec)
        }
        override suspend fun onSomeoneBehind(setBy: String, positionSec: Double) {
            behind.add(setBy to positionSec)
        }
        override suspend fun onSomeoneFastForwarded(setBy: String, positionSec: Double) {
            fastForwarded.add(setBy to positionSec)
        }
        override suspend fun onSomeonePaused(setBy: String) { paused.add(setBy) }
        override suspend fun onSomeonePlayed(setBy: String) { played.add(setBy) }
    }

    /**
     * Fake [SyncplayNetworkManager] that captures outbound writes and tracks
     * [onConnected] invocations.
     */
    private class FakeSyncplayNetworkManager(
        coroutineScope: CoroutineScope,
    ) : SyncplayNetworkManager(coroutineScope) {
        val writes = mutableListOf<String>()
        var onConnectedCalled = false

        override suspend fun connectSocket() {}
        override fun supportsTLS(): Boolean = false
        override fun terminateExistingConnection() {}
        override suspend fun writeActualString(s: String) { writes.add(s) }
        override suspend fun upgradeTls() {}
        override fun onConnected() {
            onConnectedCalled = true
            state.value = ConnectionState.CONNECTED
        }
        override fun onDisconnected() {}
        override fun onConnectionFailed() {}
    }
}
