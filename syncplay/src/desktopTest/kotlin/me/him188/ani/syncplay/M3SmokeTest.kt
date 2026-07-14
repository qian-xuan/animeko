package me.him188.ani.syncplay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.syncplay.engine.SyncplayController
import me.him188.ani.syncplay.network.KtorSyncplayNetworkManager
import me.him188.ani.syncplay.protocol.models.ConnectionState
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * M3 milestone smoke test — full watch-together UX.
 * Connects to a real syncplay server, verifies motd, sends a chat message,
 * verifies the chat appears in messageSequence, disconnects, and verifies
 * clean shutdown.
 *
 * Marked [Ignore] by default because it requires network access to syncplay.pl.
 * Remove [Ignore] or run with `--tests` filter to execute manually.
 */
@Ignore("Manual smoke test — requires network access to syncplay.pl.")
class M3SmokeTest {
    @Test
    fun full_watch_together_flow() = runBlocking {
        val scope = CoroutineScope(Job())
        try {
            val controller = SyncplayController(KtorSyncplayNetworkManager(scope), scope)

            println("=== M3 Smoke Test: Full watch-together flow ===")

            // 1. Connect
            controller.connect(
                host = "syncplay.pl",
                port = 8995,
                room = "aniko-m3-${Uuid.random().toString().take(6)}",
                username = "ani-m3-${Uuid.random().toString().take(4)}",
                password = "",
                enableTLS = false,
            )

            // 2. Wait for CONNECTED (15s timeout)
            withTimeout(15.seconds) {
                while (controller.state.value != ConnectionState.CONNECTED) delay(0.5.seconds)
            }
            println("Connected: ${controller.state.value}")

            // 3. Wait for motd
            delay(2.seconds)
            val messages = controller.messageSequence.value
            println("Messages: $messages")
            assertTrue(messages.isNotEmpty(), "Should have received at least motd")

            // 4. Send chat and verify it appears
            val chatReceived = CompletableDeferred<String>()
            val chatJob = scope.launch {
                var lastSize = controller.messageSequence.value.size
                controller.messageSequence.collect { list ->
                    for (i in lastSize until list.size) {
                        val msg = list[i]
                        println("Received: ${msg.message}")
                        if (!msg.isSystemMessage && !chatReceived.isCompleted) {
                            chatReceived.complete(msg.message)
                        }
                    }
                    lastSize = list.size
                }
            }
            delay(1.seconds) // let collector start

            controller.dispatcher.sendMessage("M3 test message")

            val received = withTimeout(10.seconds) { chatReceived.await() }
            println("Chat received: $received")
            assertTrue(received.contains("M3 test message"), "Chat message should appear in messageSequence")

            // 5. Disconnect
            chatJob.cancel()
            controller.disconnect()
            delay(1.seconds)

            // 6. Verify disconnected
            val finalState = controller.state.value
            println("Final state: $finalState")
            assertTrue(
                finalState == ConnectionState.DISCONNECTED,
                "Should be disconnected after disconnect(), but was: $finalState",
            )

            println("=== M3 Smoke Test PASSED ===")
        } finally {
            scope.cancel()
        }
    }
}
