package me.him188.ani.syncplay.engine

import kotlin.time.Instant

/**
 * A pure sync decision produced by [decideAction]. The engine's message handler
 * translates each action into the appropriate player/callback call.
 *
 * Extracted from syncplay-mobile's `RoomServerMessageHandler.onState` (lines 106-225)
 * so the riskiest sync logic is unit-testable without a player or ProtocolManager
 * dependency.
 */
sealed class SyncAction {
    /** No correction needed this pass. */
    object NoOp : SyncAction()

    /** Hard seek to [positionMs] (milliseconds). */
    data class Seek(val positionMs: Long) : SyncAction()

    /** Rewind (seek backward) to [positionSec] (seconds). */
    data class Rewind(val positionSec: Double) : SyncAction()

    /** Fast-forward (seek forward) to [positionSec] (seconds). */
    data class FastForward(val positionSec: Double) : SyncAction()

    /** Set playback speed to [rate] (e.g. 0.95 to slow down). */
    data class SlowDown(val rate: Double) : SyncAction()

    /** Reset playback speed to 1.0. */
    object ResetSpeed : SyncAction()

    /** Pause playback. */
    object Pause : SyncAction()

    /** Resume playback. */
    object Resume : SyncAction()

    /**
     * First-sync: seek to [positionMs] and apply the pause state [paused].
     * Encapsulates the one-shot "join the room and snap to the current position" flow.
     */
    data class FirstSync(val positionMs: Long, val paused: Boolean) : SyncAction()
}

/**
 * Pure sync decision function extracted from syncplay-mobile's
 * `RoomServerMessageHandler.onState` (lines 106-225).
 *
 * Takes all inputs as parameters and returns the list of [SyncAction]s the handler
 * should apply. No side effects, no player calls, no ProtocolManager mutations.
 *
 * The caller (message handler) is responsible for:
 * - Updating `ProtocolManager` state (globalPaused, globalPositionMs, lastGlobalUpdate, etc.)
 * - Managing `behindFirstDetected` based on whether a [SyncAction.FastForward] was returned:
 *   - FastForward returned -> set `behindFirstDetected = now + FASTFORWARD_RESET_THRESHOLD`
 *   - Behind condition met but no FastForward and `behindFirstDetected == null` -> set `behindFirstDetected = now`
 *   - Behind condition not met -> clear `behindFirstDetected = null`
 * - Applying the returned actions to the player and callbacks
 *
 * @param paused new pause state from server
 * @param position new position (seconds) from server
 * @param doSeek whether this State carries a seek command (null = not present)
 * @param setBy who set the state (null = no setBy field)
 * @param messageAge forward delay / message age (seconds the server position is stale)
 * @param localPositionSec local player position in seconds
 * @param currentUsername our username (for anti-self-seek)
 * @param globalPaused room's current pause state BEFORE this update
 * @param lastGlobalUpdate null = first sync; non-null = already synced before
 * @param mediaLoaded whether media is loaded (gates desync correction)
 * @param speedChanged whether speed was already adjusted for desync
 * @param canFastForward controlled room without controller OR SYNC_DONT_SLOW_WITH_ME
 * @param behindFirstDetected when behind was first detected (null = not behind)
 * @param now current time
 * @return list of actions to apply; never empty (returns [SyncAction.NoOp] if nothing to do)
 */
fun decideAction(
    paused: Boolean,
    position: Double,
    doSeek: Boolean?,
    setBy: String?,
    messageAge: Double,
    localPositionSec: Double,
    currentUsername: String,
    globalPaused: Boolean,
    lastGlobalUpdate: Instant?,
    mediaLoaded: Boolean,
    speedChanged: Boolean,
    canFastForward: Boolean,
    behindFirstDetected: Instant?,
    now: Instant,
): List<SyncAction> {
    // Match python: when the room is playing, the position the server sent us is
    // already messageAge seconds stale, so the *current* expected position is
    // position + messageAge. All threshold comparisons must use this aged value.
    val agedPosition = if (paused) position else position + messageAge

    val pausedChanged = globalPaused != paused

    // diff = local minus global, in seconds. Positive = we're ahead, negative = behind.
    val diff = localPositionSec - agedPosition

    // 4. First-sync: snap to the room position on the very first inbound State.
    if (lastGlobalUpdate == null && mediaLoaded) {
        return listOf(
            SyncAction.FirstSync(
                positionMs = (agedPosition * 1000.0).toLong(),
                paused = paused,
            )
        )
    }

    // 5. doSeek: someone explicitly seeked. Anti-self-seek: ignore our own seek echo.
    if (doSeek == true && setBy != null) {
        if (setBy == currentUsername) {
            return listOf(SyncAction.NoOp)
        }
        val actions = mutableListOf<SyncAction>()
        if (speedChanged) {
            actions.add(SyncAction.ResetSpeed)
        }
        actions.add(SyncAction.Seek(positionMs = (agedPosition * 1000.0).toLong()))
        return actions
    }

    val actions = mutableListOf<SyncAction>()

    // 6. Desync correction -- only when media is loaded and this isn't a seek.
    if (mediaLoaded && doSeek != true) {
        // a. Rewind: we're ahead by more than the threshold.
        if (diff > ProtocolManager.REWIND_THRESHOLD.toDouble()) {
            if (speedChanged) {
                actions.add(SyncAction.ResetSpeed)
            }
            actions.add(SyncAction.Rewind(positionSec = agedPosition))
        }

        // b. Fast-forward: we're persistently behind. Only in controlled rooms where
        //    we can't control (or opted in via SYNC_DONT_SLOW_WITH_ME).
        if (diff < -ProtocolManager.FASTFORWARD_BEHIND_THRESHOLD && canFastForward) {
            if (behindFirstDetected != null) {
                val durationBehind = (now - behindFirstDetected).inWholeSeconds
                if (durationBehind > (ProtocolManager.FASTFORWARD_THRESHOLD - ProtocolManager.FASTFORWARD_BEHIND_THRESHOLD)
                    && diff < -ProtocolManager.FASTFORWARD_THRESHOLD
                ) {
                    actions.add(
                        SyncAction.FastForward(
                            positionSec = agedPosition + ProtocolManager.FASTFORWARD_EXTRA_TIME,
                        )
                    )
                }
            }
            // If behindFirstDetected == null, the handler sets the timestamp; we don't
            // emit a FastForward yet (just detected, need to wait).
        }

        // c. Slow-down / reset: gradually catch up when ahead, or restore speed when back in sync.
        if (!paused) {
            if (diff > ProtocolManager.SLOWDOWN_THRESHOLD && !speedChanged && setBy != currentUsername) {
                actions.add(SyncAction.SlowDown(rate = ProtocolManager.SLOWDOWN_RATE))
            } else if (speedChanged && diff < ProtocolManager.SLOWDOWN_RESET_THRESHOLD) {
                actions.add(SyncAction.ResetSpeed)
            }
        }
    }

    // 7. Pause transition: genuine room pause-state change.
    if (pausedChanged) {
        if (paused && speedChanged) {
            actions.add(SyncAction.ResetSpeed)
        }
        if (!paused) {
            actions.add(SyncAction.Resume)
        } else {
            actions.add(SyncAction.Pause)
        }
    }

    // 8. If no actions were added, return NoOp.
    if (actions.isEmpty()) {
        return listOf(SyncAction.NoOp)
    }

    return actions
}
