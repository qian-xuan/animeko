package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.models.ConnectionState
import me.him188.ani.syncplay.protocol.models.TlsState
import me.him188.ani.syncplay.protocol.wire.HelloData
import me.him188.ani.syncplay.protocol.wire.SetData
import me.him188.ani.syncplay.protocol.wire.UserEvent
import me.him188.ani.syncplay.protocol.wire.UserSetData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [SyncplayController] using a [FakeNetworkManager] subclass.
 *
 * - Test 1: `connect()` sets host/port/tls on the network manager.
 * - Test 2: `connect()` with TLS sets `TLS_ASK`.
 * - Test 3: `sendHello()` sends a Hello with correct username/room (via connect flow).
 * - Test 4: `sendHello()` MD5-hashes the password.
 * - Test 5: `onHello` handler calls `onConnected` and sends a List request.
 * - Test 6: `onChatBroadcast` handler appends to `session.messageSequence`.
 * - Test 7: `onSet` user-join adds a user to the session list.
 * - Test 8: `onSet` user-left removes a user from the session list.
 * - Test 9: `onError` handler appends a system message to `session.messageSequence`.
 */
@OptIn(DelicateCoroutinesApi::class)
class SyncplayControllerTest {

    @Test
    fun connect_sets_host_port_and_tls_no_on_network_manager() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            controller.connect("example.com", 8997, "room1", "user1", "", enableTLS = false)
            assertEquals("example.com", fake.host)
            assertEquals(8997, fake.port)
            assertEquals(TlsState.TLS_NO, fake.tls)
            assertTrue(fake.connectSocketCalled)
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun connect_with_tls_sets_tls_ask() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            controller.connect("example.com", 8997, "room1", "user1", "", enableTLS = true)
            assertEquals(TlsState.TLS_ASK, fake.tls)
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun sendHello_sends_hello_with_correct_username_and_room() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            controller.connect("example.com", 8997, "room1", "user1", "", enableTLS = false)
            // connect() triggers onReadyForHandshake → dispatcher.sendHello() → send(Hello) → writeActualString
            assertEquals(1, fake.writes.size)
            val written = fake.writes[0]
            assertTrue(written.contains("Hello"), "Expected Hello message, got: $written")
            assertTrue(written.contains("user1"), "Expected username 'user1', got: $written")
            assertTrue(written.contains("room1"), "Expected room 'room1', got: $written")
            assertTrue(written.contains("1.2.255"), "Expected legacy version, got: $written")
            assertTrue(written.contains("1.7.5"), "Expected protocol version, got: $written")
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun sendHello_hashes_password_with_md5() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            controller.connect("example.com", 8997, "room1", "user1", "secretpass", enableTLS = false)
            val written = fake.writes[0]
            val expectedHash = md5Hex("secretpass")
            assertTrue(written.contains(expectedHash), "Expected MD5 hash '$expectedHash', got: $written")
            assertTrue(!written.contains("secretpass"), "Password should not appear in plaintext: $written")
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun onHello_calls_onConnected_and_sends_list_request() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            fake.handler.onHello(
                WireMessage.Hello(HelloData(motd = "Welcome to the server!"))
            )

            assertTrue(fake.onConnectedCalled, "onConnected should be called after Hello")
            // SyncplayMessageHandler.onHello sends a List request after Hello.
            assertTrue(
                fake.writes.any { it.contains("List") },
                "Should send List request after Hello, got: ${fake.writes}",
            )
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun onChatBroadcast_appends_to_message_sequence() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            fake.handler.onChatBroadcast(WireMessage.chatBroadcast("alice", "hello world"))

            val messages = controller.messageSequence.value
            assertEquals(1, messages.size)
            assertEquals("alice", messages[0].username)
            assertEquals("hello world", messages[0].message)
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun onSet_user_join_adds_user_to_list() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            fake.handler.onSet(
                WireMessage.Set(
                    SetData(
                        user = mapOf(
                            "bob" to UserSetData(event = UserEvent(joined = JsonPrimitive(true)))
                        )
                    )
                )
            )
            val users = controller.session.userList.value
            assertEquals(1, users.size)
            assertEquals("bob", users[0].name)
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun onSet_user_left_removes_user_from_list() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            // First add a user
            fake.handler.onSet(
                WireMessage.Set(
                    SetData(
                        user = mapOf(
                            "bob" to UserSetData(event = UserEvent(joined = JsonPrimitive(true)))
                        )
                    )
                )
            )
            assertEquals(1, controller.session.userList.value.size)

            // Then remove the user
            fake.handler.onSet(
                WireMessage.Set(
                    SetData(
                        user = mapOf(
                            "bob" to UserSetData(event = UserEvent(left = JsonPrimitive(true)))
                        )
                    )
                )
            )
            assertEquals(0, controller.session.userList.value.size)
        } finally {
            controller.disconnect()
        }
    }

    @Test
    fun onError_appends_system_message_to_message_sequence() = runBlocking {
        val fake = FakeNetworkManager(this)
        val controller = SyncplayController(fake, this)
        try {
            fake.handler.onError(WireMessage.error("Invalid password"))

            val messages = controller.messageSequence.value
            assertEquals(1, messages.size)
            assertEquals("Invalid password", messages[0].message)
            assertTrue(messages[0].isSystemMessage, "Error message should be marked as system message")
        } finally {
            controller.disconnect()
        }
    }

    // -- Test fixture --

    private class FakeNetworkManager(
        coroutineScope: CoroutineScope,
    ) : SyncplayNetworkManager(coroutineScope) {
        val writes = mutableListOf<String>()
        var connectSocketCalled = false
        var onConnectedCalled = false

        override suspend fun connectSocket() {
            connectSocketCalled = true
        }

        override fun supportsTLS(): Boolean = false

        override fun terminateExistingConnection() {}

        override suspend fun writeActualString(s: String) {
            writes.add(s)
        }

        override suspend fun upgradeTls() {}

        override fun onConnected() {
            onConnectedCalled = true
            state.value = ConnectionState.CONNECTED
        }

        override fun onDisconnected() {}

        override fun onConnectionFailed() {}
    }
}
