package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.him188.ani.syncplay.protocol.wire.FileData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for the T4.3 bridge anti-loop logic.
 *
 * These tests exercise the pure functions/classes in [BridgeLogic.kt] without
 * a player dependency. The 7 cases (a-g) match the acceptance criteria in
 * the plan:
 *
 * - (a) sync-driven pause (enableSync=true) → NO bridge-direct outbound State
 * - (b) user-originated pause (enableSync=false) → outbound State IS emitted
 * - (c) inbound Set.file with episodeId == current → switchEpisode NOT called
 * - (d) inbound Set.file triggers switchEpisode (enableFileSync=true) → NO outbound Set.file
 * - (e) seek-timing: multiple currentPositionMillis emissions → ALL suppressed within window
 * - (f) non-match: Set.file.name not matching regex → ignored, no crash, no switch
 * - (g) switch-gap: SharedFlow(replay=1) delivers last file immediately on collection start
 */
class BridgeLogicTest {

    // -- (a) sync-driven pause suppresses bridge-direct outbound --

    @Test
    fun a_sync_driven_pause_suppresses_bridge_outbound() {
        val antiLoop = BridgeAntiLoop()
        val protocol = ProtocolManager()

        // Simulate: sync-driven pause is about to happen.
        antiLoop.armForPlaybackChange()
        assertTrue(antiLoop.enableSync.value, "enableSync should be true after armForPlaybackChange")

        // The playbackState collector checks: should we suppress?
        val suppressed = antiLoop.shouldSuppressPlaybackOutbound()
        assertTrue(suppressed, "Sync-driven pause should suppress bridge-direct outbound")

        // The bridge does NOT call dispatcher.controlPlayback (suppressed).
        // The engine's divergence broadcast (ChannelHealthMonitor) fires instead,
        // carrying clientIgnFly. Simulate that broadcast:
        val clientIgnFlyBefore = protocol.clientIgnFly
        protocol.buildStatePacket(
            serverTime = null,
            doSeek = null,
            position = null,
            isLocalStateChange = true,
            play = false,
        )
        assertEquals(
            clientIgnFlyBefore + 1,
            protocol.clientIgnFly,
            "clientIgnFly should be incremented by the engine's divergence broadcast (isLocalStateChange=true)",
        )
    }

    // -- (b) user-originated pause emits outbound State --

    @Test
    fun b_user_originated_pause_emits_outbound_state() {
        val antiLoop = BridgeAntiLoop()

        // User-originated: enableSync is false (not armed).
        assertFalse(antiLoop.enableSync.value, "enableSync should be false by default")

        // The playbackState collector checks: should we suppress?
        val suppressed = antiLoop.shouldSuppressPlaybackOutbound()
        assertFalse(suppressed, "User-originated pause should NOT suppress outbound State")

        // The bridge calls dispatcher.controlPlayback → outbound State IS emitted.
    }

    // -- (c) inbound Set.file with episodeId == current → no switch --

    @Test
    fun c_inbound_file_with_same_episode_does_not_switch() {
        val currentEpisodeId = 42
        val filename = encodeIdentityInFilename(subjectId = 1, episodeId = currentEpisodeId)

        val parsed = parseIdentityInFilename(filename)
        assertNotNull(parsed, "Parsed identity should not be null for valid filename")
        assertEquals(1 to 42, parsed)

        val shouldSwitch = shouldSwitchEpisode(parsed!!.second, currentEpisodeId)
        assertFalse(shouldSwitch, "Should NOT switch when episodeId == current")
    }

    // -- (d) inbound Set.file triggers switchEpisode → NO outbound Set.file re-announced --

    @Test
    fun d_sync_driven_switch_suppresses_outbound_file() {
        val antiLoop = BridgeAntiLoop()

        // Simulate: sync-driven switchEpisode is about to happen.
        antiLoop.armForFileSwitch()
        assertTrue(antiLoop.enableFileSync.value, "enableFileSync should be true after armForFileSwitch")

        // The media-load collector checks: should we suppress the outbound Set.file?
        val suppressed = antiLoop.shouldSuppressFileOutbound()
        assertTrue(suppressed, "Sync-driven switchEpisode should suppress outbound Set.file")

        // Flag resets after first suppressed event.
        assertFalse(antiLoop.enableFileSync.value, "enableFileSync should reset after first suppression")

        // Subsequent media-load: not suppressed (outbound Set.file sent normally).
        val suppressedAgain = antiLoop.shouldSuppressFileOutbound()
        assertFalse(suppressedAgain, "Second media-load should NOT be suppressed")
    }

    // -- (e) seek-timing: multiple emissions all suppressed within window --

    @Test
    fun e_seek_window_suppresses_all_emissions_until_stabilized() {
        val antiLoop = BridgeAntiLoop()
        val targetMs = 5000L
        val armTime = 1000L

        // Simulate: sync-driven seekTo(5000) at time 1000.
        antiLoop.armForSeek(targetMs, armTime)
        assertTrue(antiLoop.enableSync.value, "enableSync should be true after armForSeek")

        // Position emission 1: far from target → suppressed.
        val s1 = antiLoop.shouldSuppressPositionOutbound(currentPositionMs = 1000L, nowMs = 1100L)
        assertTrue(s1, "First emission (far from target) should be suppressed")
        assertTrue(antiLoop.enableSync.value, "enableSync should still be true (window active)")

        // Position emission 2: closer but still > threshold → suppressed.
        val s2 = antiLoop.shouldSuppressPositionOutbound(currentPositionMs = 3500L, nowMs = 1200L)
        assertTrue(s2, "Second emission (still > threshold from target) should be suppressed")
        assertTrue(antiLoop.enableSync.value, "enableSync should still be true (window active)")

        // Position emission 3: within threshold of target → window closes, flag resets.
        val s3 = antiLoop.shouldSuppressPositionOutbound(currentPositionMs = 4900L, nowMs = 1300L)
        assertFalse(s3, "Emission within threshold should NOT be suppressed (window closed)")
        assertFalse(antiLoop.enableSync.value, "enableSync should be false after window closes")

        // Subsequent emission: not suppressed (normal outbound).
        val s4 = antiLoop.shouldSuppressPositionOutbound(currentPositionMs = 5000L, nowMs = 1400L)
        assertFalse(s4, "Emission after window closed should NOT be suppressed")
    }

    @Test
    fun e_seek_window_resets_on_timeout_if_position_never_stabilizes() {
        val antiLoop = BridgeAntiLoop()
        val targetMs = 5000L
        val armTime = 1000L

        antiLoop.armForSeek(targetMs, armTime)

        // Position never reaches target — all emissions suppressed.
        val s1 = antiLoop.shouldSuppressPositionOutbound(currentPositionMs = 1000L, nowMs = 1100L)
        assertTrue(s1)

        val s2 = antiLoop.shouldSuppressPositionOutbound(currentPositionMs = 2000L, nowMs = 1500L)
        assertTrue(s2)

        // After 2s timeout (armTime=1000, now=3100 → elapsed=2100 > 2000) → window closes.
        val s3 = antiLoop.shouldSuppressPositionOutbound(currentPositionMs = 2000L, nowMs = 3100L)
        assertFalse(s3, "After timeout, suppression should end even if position hasn't stabilized")
        assertFalse(antiLoop.enableSync.value, "enableSync should be false after timeout")
    }

    // -- (f) non-match: filename not matching regex → ignored --

    @Test
    fun f_non_matching_filename_is_ignored() {
        // Kazumi-style: bangumiId[episodeNumber] — different semantics, but same regex shape.
        // Actually, the regex (\d+)\[(\d+)] WOULD match this. The plan says "gracefully
        // ignore" non-matching. Let's test truly non-matching filenames.
        val nonMatching = listOf(
            "random_video_file.mp4",
            "episode_01.mkv",
            "[SubGroup] Anime - 01 [1080p].mkv",
            "",
            "no_brackets_here.mp4",
        )

        for (filename in nonMatching) {
            val result = parseIdentityInFilename(filename)
            assertNull(result, "Non-matching filename '$filename' should return null, got: $result")
        }

        // Matching filename → parsed correctly.
        val matching = "123[456]"
        val parsed = parseIdentityInFilename(matching)
        assertNotNull(parsed)
        assertEquals(123 to 456, parsed)
    }

    // -- (g) switch-gap: SharedFlow(replay=1) delivers last file immediately --

    @Test
    fun g_switch_gap_shared_flow_replay_delivers_last_file_immediately() = runBlocking {
        // Simulate: controller exposes inbound Set.file as SharedFlow(replay=1).
        val inboundFileFlow = MutableSharedFlow<FileData?>(
            replay = 1,
            extraBufferCapacity = 1,
        )

        // 1. Old session's backgroundTaskScope is cancelled (bridge is dead).
        // 2. During the gap, an inbound Set.file arrives → emitted to the SharedFlow.
        inboundFileFlow.emit(FileData(name = "123[456]", duration = 600.0, size = "1000"))

        // 3. New onStart launches the steady-state collector.
        //    SharedFlow(replay=1) delivers the last file IMMEDIATELY on collection start.
        val received = withTimeout(5.seconds) {
            inboundFileFlow.first()
        }

        // The collector got the file via replay=1 immediately.
        assertNotNull(received, "Collector should receive the file via SharedFlow(replay=1)")
        assertEquals("123[456]", received!!.name)

        // Parse the identity and decide to switch.
        val parsed = parseIdentityInFilename(received.name!!)
        assertNotNull(parsed)
        assertEquals(123 to 456, parsed)

        // If episodeId != current → switchEpisode would be called.
        val currentEpisodeId = 999
        assertTrue(shouldSwitchEpisode(parsed!!.second, currentEpisodeId))
    }

    @Test
    fun g_switch_gap_collector_gets_file_even_if_emitted_before_collection_starts() = runBlocking {
        val inboundFileFlow = MutableSharedFlow<FileData?>(
            replay = 1,
            extraBufferCapacity = 1,
        )
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())

        // Emit a file BEFORE any collector exists.
        inboundFileFlow.tryEmit(FileData(name = "789[012]", duration = 1200.0, size = "2000"))

        // Now launch a collector — it should get the file immediately via replay.
        val received = withTimeout(5.seconds) {
            inboundFileFlow.first()
        }

        assertEquals("789[012]", received?.name)

        scope.cancel()
    }

    // -- Helper --

    private fun <T> assertNotNull(value: T, message: String? = null) {
        kotlin.test.assertNotNull(value, message)
    }
}
