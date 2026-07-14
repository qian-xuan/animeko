package me.him188.ani.syncplay.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [decideAction] -- the pure sync decision logic extracted from
 * syncplay-mobile's `RoomServerMessageHandler.onState`.
 *
 * Each test pins one branch of the decision tree. Parameters not under test
 * are set to neutral defaults so only the target branch can fire.
 */
class SyncActionTest {

    @Test
    fun rewind_when_local_ahead_by_6s() {
        // diff = 6 > REWIND_THRESHOLD(4) -> Rewind
        // paused = true so agedPosition = position (no messageAge added)
        // paused = true also disables SlowDown (!paused is false)
        val actions = decideAction(
            paused = true,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 106.0,
            currentUsername = "me",
            globalPaused = true,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(
            listOf(SyncAction.Rewind(positionSec = 100.0)),
            actions,
        )
    }

    @Test
    fun rewind_resets_speed_when_speedChanged() {
        // diff = 6 > 4, speedChanged = true -> ResetSpeed + Rewind
        val actions = decideAction(
            paused = true,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 106.0,
            currentUsername = "me",
            globalPaused = true,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = true,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(
            listOf(SyncAction.ResetSpeed, SyncAction.Rewind(positionSec = 100.0)),
            actions,
        )
    }

    @Test
    fun fastForward_when_local_behind_by_7s_and_canFastForward() {
        // diff = -7 < -FASTFORWARD_THRESHOLD(5.0), canFastForward = true,
        // behindFirstDetected set 10s ago -> durationBehind = 10 > 3.25
        val now = Clock.System.now()
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 93.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = true,
            behindFirstDetected = now - 10.seconds,
            now = now,
        )

        assertEquals(
            listOf(SyncAction.FastForward(positionSec = 100.25)),
            actions,
        )
    }

    @Test
    fun fastForward_returns_noOp_when_cannotFastForward() {
        // Same diff but canFastForward = false -> NoOp
        val now = Clock.System.now()
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 93.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = now - 10.seconds,
            now = now,
        )

        assertEquals(listOf(SyncAction.NoOp), actions)
    }

    @Test
    fun fastForward_not_triggered_when_behindFirstDetected_null() {
        // Behind condition met but behindFirstDetected == null -> just detected, no FastForward yet
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 93.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = true,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(listOf(SyncAction.NoOp), actions)
    }

    @Test
    fun slowDown_when_local_ahead_by_2s_and_playing() {
        // diff = 2 > SLOWDOWN_THRESHOLD(1.5), !paused, !speedChanged, setBy != self
        // Note: SlowDown fires when local is AHEAD (diff > 0), not behind.
        // Slowing down lets others catch up.
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = "other",
            messageAge = 0.0,
            localPositionSec = 102.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(
            listOf(SyncAction.SlowDown(rate = ProtocolManager.SLOWDOWN_RATE)),
            actions,
        )
    }

    @Test
    fun seek_when_doSeek_and_setBy_other() {
        // doSeek = true, setBy = "other" != currentUsername -> Seek
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = true,
            setBy = "other",
            messageAge = 0.0,
            localPositionSec = 100.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(
            listOf(SyncAction.Seek(positionMs = 100_000L)),
            actions,
        )
    }

    @Test
    fun seek_resets_speed_when_doSeek_and_speedChanged() {
        // doSeek = true, setBy = "other", speedChanged = true -> ResetSpeed + Seek
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = true,
            setBy = "other",
            messageAge = 0.0,
            localPositionSec = 100.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = true,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(
            listOf(SyncAction.ResetSpeed, SyncAction.Seek(positionMs = 100_000L)),
            actions,
        )
    }

    @Test
    fun pause_when_pausedChanged_and_paused_true() {
        // globalPaused = false, paused = true -> pausedChanged = true, paused = true -> Pause
        val actions = decideAction(
            paused = true,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 100.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(listOf(SyncAction.Pause), actions)
    }

    @Test
    fun resume_when_pausedChanged_and_paused_false() {
        // globalPaused = true, paused = false -> pausedChanged = true, !paused -> Resume
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 100.0,
            currentUsername = "me",
            globalPaused = true,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(listOf(SyncAction.Resume), actions)
    }

    @Test
    fun firstSync_when_lastGlobalUpdate_null_and_mediaLoaded() {
        // lastGlobalUpdate = null, mediaLoaded = true -> FirstSync
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 100.0,
            currentUsername = "me",
            globalPaused = true,
            lastGlobalUpdate = null,
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(
            listOf(SyncAction.FirstSync(positionMs = 100_000L, paused = false)),
            actions,
        )
    }

    @Test
    fun noOp_when_doSeek_and_setBy_self() {
        // doSeek = true, setBy = currentUsername -> anti-self-seek -> NoOp
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = true,
            setBy = "me",
            messageAge = 0.0,
            localPositionSec = 100.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(listOf(SyncAction.NoOp), actions)
    }

    @Test
    fun noOp_when_nothing_to_do() {
        // diff = 0, no doSeek, no pausedChanged, media loaded -> NoOp
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 100.0,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = false,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertEquals(listOf(SyncAction.NoOp), actions)
    }

    @Test
    fun resetSpeed_when_speedChanged_and_diff_below_reset_threshold() {
        // speedChanged = true, diff = 0.05 < SLOWDOWN_RESET_THRESHOLD(0.1), !paused
        // -> ResetSpeed (the else-if branch of SlowDown)
        val actions = decideAction(
            paused = false,
            position = 100.0,
            doSeek = null,
            setBy = null,
            messageAge = 0.0,
            localPositionSec = 100.05,
            currentUsername = "me",
            globalPaused = false,
            lastGlobalUpdate = Clock.System.now(),
            mediaLoaded = true,
            speedChanged = true,
            canFastForward = false,
            behindFirstDetected = null,
            now = Clock.System.now(),
        )

        assertTrue(
            actions.contains(SyncAction.ResetSpeed),
            "Expected ResetSpeed when speedChanged and diff < reset threshold, got: $actions",
        )
    }
}
