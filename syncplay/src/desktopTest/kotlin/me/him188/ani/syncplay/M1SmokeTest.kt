package me.him188.ani.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.syncplay.engine.ChatMessage
import me.him188.ani.syncplay.engine.SyncplayController
import me.him188.ani.syncplay.network.KtorSyncplayNetworkManager
import me.him188.ani.syncplay.protocol.models.ConnectionState
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * M1 milestone smoke test — connects to a real syncplay server, sends Hello,
 * logs the motd + inbound State, and stays connected for ≥10s.
 *
 * Marked [Ignore] by default because it requires network access to syncplay.pl.
 * Remove [Ignore] or run with `--tests` filter to execute manually.
 */
@Ignore("Manual smoke test — requires network access to syncplay.pl. Remove @Ignore to run.")
class M1SmokeTest {
    @Test
    fun connect_and_handshake_to_real_server() = runBlocking {
        val scope = CoroutineScope(Job())
        val networkManager = KtorSyncplayNetworkManager(scope)
        val controller = SyncplayController(networkManager, scope)

        val messages = mutableListOf<ChatMessage>()
        val messageJob = scope.launch {
            var lastSize = 0
            controller.messageSequence.collect { list ->
                for (i in lastSize until list.size) {
                    println("SERVER MESSAGE: ${list[i].message}")
                    messages.add(list[i])
                }
                lastSize = list.size
            }
        }

        try {
            println("=== M1 Smoke Test: Connecting to syncplay.pl:8995 ===")

            controller.connect(
                host = "syncplay.pl",
                port = 8995,
                room = "aniko-test-${Uuid.random().toString().take(6)}",
                username = "ani-test-${Uuid.random().toString().take(6)}",
                password = "",
                enableTLS = false,
            )

            // Wait for at least one message with 15s timeout
            withTimeout(15.seconds) {
                while (messages.isEmpty()) { delay(0.5.seconds) }
            }

            println("=== First message received: ${messages.first().message} ===")
            assertTrue(messages.isNotEmpty(), "Should have received at least one message (motd/state)")

            // Stay connected for 10s, collecting any further messages
            println("=== Waiting 10s for additional messages... ===")
            delay(10.seconds)

            // Verify we stayed connected
            val finalState = controller.state.value
            println("=== Final connection state: $finalState ===")
            assertTrue(
                finalState == ConnectionState.CONNECTED,
                "Should still be connected after 10s, but was: $finalState",
            )

            println("=== M1 Smoke Test PASSED ===")
        } finally {
            messageJob.cancel()
            controller.disconnect()
            scope.cancel()
        }
    }
}
