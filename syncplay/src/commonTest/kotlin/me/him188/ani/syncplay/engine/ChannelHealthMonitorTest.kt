package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.syncplay.network.SyncplayNetworkManager
import me.him188.ani.syncplay.protocol.WireMessage
import me.him188.ani.syncplay.protocol.models.ConnectionState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [ChannelHealthMonitor].
 *
 * Uses `runBlocking` with real delays — the watchdog/list-probe use [Clock.System.now]
 * (real wall-clock) for their timeout checks, so virtual time from `runTest` would not
 * advance those checks. The playback-broadcast is event-driven and fast.
 *
 * - Test 1: `start()` launches 3 jobs; `stop()` cancels all of them.
 * - Test 2: playback-broadcast sends a State when isPlayingFlow diverges from expectedPaused.
 * - Test 3: no State sent when isPlayingFlow matches expectedPaused.
 * - Test 4: watchdog fires onDisconnected after STATE_TIMEOUT_SECONDS of silence.
 *
 * The fake network manager overrides [writeActualString] to capture writes synchronously
 * and exposes a [CompletableDeferred] so the test can await the async `sendAsync` write.
 */
class ChannelHealthMonitorTest {

    private val scope = CoroutineScope(Job())
    private lateinit var fake: FakeSyncplayNetworkManager

    @AfterTest
    fun tearDown() {
        fake.invalidate()
        scope.cancel()
    }

    @Test
    fun start_launches_three_jobs_and_stop_cancels_them() = runBlocking {
        val protocol = ProtocolManager()
        fake = FakeSyncplayNetworkManager(scope)
        val monitor = ChannelHealthMonitor(
            scope = scope,
            networkManager = fake,
            protocol = protocol,
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        monitor.start()
        try {
            val children = scope.coroutineContext[Job]!!.children.toList()
            // 1 from FakeSyncplayNetworkManager's init consumer + 3 from the monitor = 4.
            // Verify at least 3 are active (the monitor's jobs); the network manager's
            // inbound consumer is an extra child we don't assert on.
            assertTrue(
                children.count { it.isActive } >= 3,
                "Expected at least 3 active jobs after start(), got ${children.count { it.isActive }}",
            )
        } finally {
            monitor.stop()
        }

        // After stop(), the monitor's 3 jobs must be cancelled. The network manager's
        // inbound consumer (launched in its init) is still active, so we check that the
        // active count dropped by at least 3.
        val childrenAfter = scope.coroutineContext[Job]!!.children.toList()
        assertTrue(
            childrenAfter.count { it.isActive } <= 1,
            "Expected at most 1 active job after stop() (network consumer only), got ${childrenAfter.count { it.isActive }}",
        )
    }

    @Test
    fun playback_broadcast_sends_state_on_divergence() = runBlocking {
        val protocol = ProtocolManager()
        fake = FakeSyncplayNetworkManager(scope)
        fake.state.value = ConnectionState.CONNECTED
        // expectedPaused = true -> expectedPlaying = false
        protocol.noteExpectedPlaybackState(paused = true)

        val isPlayingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val monitor = ChannelHealthMonitor(
            scope = scope,
            networkManager = fake,
            protocol = protocol,
            isPlayingFlow = isPlayingFlow,
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        monitor.start()
        try {
            // Divergence: expected paused, but player is playing.
            isPlayingFlow.value = true

            // sendAsync launches on Dispatchers.IO; await the write.
            withTimeout(5.seconds) { fake.firstWriteCompleted.await() }

            assertTrue(
                fake.writes.any { it.contains("State") },
                "Expected a State packet to be sent on divergence, got: ${fake.writes}",
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun no_broadcast_when_no_divergence() = runBlocking {
        val protocol = ProtocolManager()
        fake = FakeSyncplayNetworkManager(scope)
        fake.state.value = ConnectionState.CONNECTED
        // expectedPaused = true -> expectedPlaying = false
        protocol.noteExpectedPlaybackState(paused = true)

        val isPlayingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
        val monitor = ChannelHealthMonitor(
            scope = scope,
            networkManager = fake,
            protocol = protocol,
            isPlayingFlow = isPlayingFlow,
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        monitor.start()
        try {
            // No divergence: expected paused, player is paused.
            isPlayingFlow.value = false
            // Give the collector time to process (and NOT send).
            delay(1.seconds)

            assertTrue(
                fake.writes.none { it.contains("State") },
                "Expected no State packet when there is no divergence, got: ${fake.writes}",
            )
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun watchdog_fires_onDisconnected_after_timeout() = runBlocking {
        val protocol = ProtocolManager()
        fake = FakeSyncplayNetworkManager(scope)
        fake.state.value = ConnectionState.CONNECTED

        val monitor = ChannelHealthMonitor(
            scope = scope,
            networkManager = fake,
            protocol = protocol,
            coroutineDispatcher = Dispatchers.Unconfined,
        )

        monitor.start()
        try {
            // start() seeds lastStateReceivedAt = now; override to 20s ago (> 15s timeout).
            protocol.lastStateReceivedAt = Clock.System.now() - 20.seconds

            // Watchdog ticks every 5s; wait for one tick + margin.
            delay(6.seconds)

            assertTrue(
                fake.onDisconnectedCalled,
                "Watchdog should have fired onDisconnected() after ${ProtocolManager.STATE_TIMEOUT_SECONDS}s of silence",
            )
            assertTrue(
                fake.terminateCalled,
                "Watchdog should have called terminateExistingConnection() before onDisconnected()",
            )
        } finally {
            monitor.stop()
        }
    }

    // -- Test fixture --

    /**
     * Fake [SyncplayNetworkManager] that captures writes via [writeActualString]
     * and tracks [onDisconnected] / [terminateExistingConnection] calls.
     *
     * [firstWriteCompleted] lets tests await the first async `sendAsync` write
     * (which runs on `Dispatchers.IO`).
     */
    private class FakeSyncplayNetworkManager(
        coroutineScope: CoroutineScope,
    ) : SyncplayNetworkManager(coroutineScope) {
        private val _writes = mutableListOf<String>()
        val writes: List<String>
            get() = synchronized(_writes) { _writes.toList() }

        val firstWriteCompleted = CompletableDeferred<Unit>()

        var onDisconnectedCalled = false
            private set
        var terminateCalled = false
            private set

        override suspend fun connectSocket() {}
        override fun supportsTLS(): Boolean = false
        override fun terminateExistingConnection() { terminateCalled = true }
        override suspend fun writeActualString(s: String) {
            synchronized(_writes) { _writes.add(s) }
            if (!firstWriteCompleted.isCompleted) firstWriteCompleted.complete(Unit)
        }
        override suspend fun upgradeTls() {}
        override fun onConnected() { state.value = ConnectionState.CONNECTED }
        override fun onDisconnected() { onDisconnectedCalled = true }
        override fun onConnectionFailed() {}
    }
}
