package me.him188.ani.syncplay.engine

import me.him188.ani.syncplay.protocol.wire.IgnoringOnTheFlyData

/**
 * Result of processing the `ignoringOnTheFly` block: whether the inbound `State`
 * should be fed to the reaction algorithm or skipped.
 */
sealed class IgnFlyResult {
    /** The state should be processed (counter is 0 after processing). */
    object Process : IgnFlyResult()

    /** The state should be skipped (still waiting for our echo to come back). */
    object Skip : IgnFlyResult()
}

/**
 * The outcome of [processIgnoringOnTheFly]: the process/skip decision plus the
 * updated counter values the caller must write back to `ProtocolManager`.
 */
data class IgnFlyProcessingResult(
    val result: IgnFlyResult,
    val newClientIgnFly: Int,
    val newServerIgnFly: Int,
)

/**
 * Processes the `ignoringOnTheFly` block from an inbound `State` message and
 * decides whether the state should be processed or skipped.
 *
 * This is the anti-feedback gate: when we originate a state change we increment
 * `clientIgnFly` so that our own change echoing back from the server does not
 * re-trigger the rewind/fastforward/slowdown logic. Once the server acknowledges
 * the change (or the echo matches), the counter resets to 0 and processing resumes.
 *
 * Logic (ported from syncplay-mobile `RoomServerMessageHandler.kt:79-88` + the
 * `clientIgnFly == 0` gate on line 106):
 *
 * - If `ignFly.server != null`: the server is acknowledging our change. Set
 *   `serverIgnFly = ignFly.server`, reset `clientIgnFly = 0` -> [IgnFlyResult.Process].
 *   The state IS processed, but since it is an echo of our own change the diff
 *   is ~0, so [decideAction] produces [SyncAction.NoOp].
 * - If `ignFly.client != null` (and `server` is null):
 *   - If `clientIgnFly == ignFly.client`: the echo matched -> reset
 *     `clientIgnFly = 0` -> [IgnFlyResult.Process].
 *   - If `clientIgnFly != ignFly.client`: still waiting for the echo -> keep
 *     `clientIgnFly` -> [IgnFlyResult.Skip].
 * - If `ignFly` is null or both fields are null: keep counters unchanged.
 *   [IgnFlyResult.Process] when `clientIgnFly == 0`, [IgnFlyResult.Skip] otherwise.
 *
 * The caller writes `newClientIgnFly` / `newServerIgnFly` back to `ProtocolManager`
 * and gates [decideAction] on `result == IgnFlyResult.Process`.
 *
 * @param ignFly the `ignoringOnTheFly` block from the inbound `State`, or null
 * @param clientIgnFly current `ProtocolManager.clientIgnFly` value
 * @param serverIgnFly current `ProtocolManager.serverIgnFly` value
 * @return the process/skip decision and the updated counter values
 */
fun processIgnoringOnTheFly(
    ignFly: IgnoringOnTheFlyData?,
    clientIgnFly: Int,
    serverIgnFly: Int,
): IgnFlyProcessingResult {
    if (ignFly == null) {
        val result = if (clientIgnFly == 0) IgnFlyResult.Process else IgnFlyResult.Skip
        return IgnFlyProcessingResult(result, clientIgnFly, serverIgnFly)
    }

    if (ignFly.server != null) {
        // Server acknowledges our change: set serverIgnFly, reset clientIgnFly.
        // The state IS processed (clientIgnFly is now 0), but since it's an echo
        // of our own change, diff ~ 0 -> decideAction produces NoOp.
        return IgnFlyProcessingResult(IgnFlyResult.Process, 0, ignFly.server)
    }

    if (ignFly.client != null) {
        return if (clientIgnFly == ignFly.client) {
            // Match: the echo confirms our change. Reset counter and process.
            IgnFlyProcessingResult(IgnFlyResult.Process, 0, serverIgnFly)
        } else {
            // Mismatch: still waiting for our echo. Skip to avoid re-triggering.
            IgnFlyProcessingResult(IgnFlyResult.Skip, clientIgnFly, serverIgnFly)
        }
    }

    // Both fields null inside the ignFly block: treat as if there were no ignFly.
    val result = if (clientIgnFly == 0) IgnFlyResult.Process else IgnFlyResult.Skip
    return IgnFlyProcessingResult(result, clientIgnFly, serverIgnFly)
}
