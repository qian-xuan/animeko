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
 * M2 milestone smoke test — two-instance playback sync.
 * Creates two [SyncplayController] instances in the same room and verifies
 * that chat messages from A are received by B.
 *
 * Marked [Ignore] by default because it requires network access to syncplay.pl.
 * Remove [Ignore] or run with `--tests` filter to execute manually.
 */
@Ignore("Manual smoke test — requires network access to syncplay.pl and two controller instances.")
class M2SmokeTest {
    @Test
    fun two_instance_sync_chat() = runBlocking {
        val scope = CoroutineScope(Job())
        try {
            val controllerA = SyncplayController(KtorSyncplayNetworkManager(scope), scope)
            val controllerB = SyncplayController(KtorSyncplayNetworkManager(scope), scope)

            val room = "aniko-m2-${Uuid.random().toString().take(6)}"
            val nameA = "ani-A-${Uuid.random().toString().take(4)}"
            val nameB = "ani-B-${Uuid.random().toString().take(4)}"

            println("=== M2 Smoke Test: Connecting two instances to room $room ===")

            controllerA.connect("syncplay.pl", 8995, room, nameA, "", false)
            controllerB.connect("syncplay.pl", 8995, room, nameB, "", false)

            // Wait for both to connect (15s timeout each)
            withTimeout(15.seconds) {
                while (controllerA.state.value != ConnectionState.CONNECTED) delay(0.5.seconds)
            }
            println("A connected: ${controllerA.state.value}")

            withTimeout(15.seconds) {
                while (controllerB.state.value != ConnectionState.CONNECTED) delay(0.5.seconds)
            }
            println("B connected: ${controllerB.state.value}")

            // Wait for user list to populate
            delay(2.seconds)
            println("A userList: ${controllerA.userList.value}")
            println("B userList: ${controllerB.userList.value}")

            // Test: A sends chat, B receives
            val chatReceived = CompletableDeferred<String>()
            val chatJob = scope.launch {
                var lastSize = controllerB.messageSequence.value.size
                controllerB.messageSequence.collect { list ->
                    for (i in lastSize until list.size) {
                        val msg = list[i]
                        println("B received: ${msg.message}")
                        if (!msg.isSystemMessage && !chatReceived.isCompleted) {
                            chatReceived.complete(msg.message)
                        }
                    }
                    lastSize = list.size
                }
            }
            delay(1.seconds) // let collector start

            controllerA.dispatcher.sendMessage("hello from A")

            val received = withTimeout(10.seconds) { chatReceived.await() }
            println("B received chat: $received")
            assertTrue(received.contains("hello from A"), "B should receive chat from A")

            println("=== M2 Smoke Test PASSED ===")
        } finally {
            scope.cancel()
        }
    }
}
