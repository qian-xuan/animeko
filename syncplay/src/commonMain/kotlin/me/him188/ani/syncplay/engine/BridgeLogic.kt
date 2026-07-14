package me.him188.ani.syncplay.engine

import kotlinx.atomicfu.atomic
import kotlin.math.abs

/**
 * Anti-loop flag manager for the player bridge (T4.3).
 *
 * Tracks whether the current player state change was sync-driven (should be
 * suppressed from outbound broadcast) or user-originated (should be broadcast).
 *
 * Two flags:
 * - [enableSync] — gates playback-state and position outbound (pause/play/seek).
 * - [enableFileSync] — gates file-load outbound (Set.file announce on media load).
 *
 * The seek-suppression window ([SeekSuppressionWindow]) extends [enableSync]
 * across multiple `currentPositionMillis` emissions that a single `seekTo()`
 * produces as the position settles. Resetting after the first emission would
 * miss subsequent ones and cause spurious outbound `State` packets.
 */
class BridgeAntiLoop {
    val enableSync = atomic(false)
    val enableFileSync = atomic(false)

    private val seekWindow = SeekSuppressionWindow()

    /**
     * Called BEFORE a sync-driven `player.pause()` or `player.resume()`.
     * Arms [enableSync] so the resulting `playbackState` emission is suppressed.
     * No seek window is armed — the flag resets after the first suppressed
     * playback-state change (see [shouldSuppressPlaybackOutbound]).
     */
    fun armForPlaybackChange() {
        enableSync.value = true
    }

    /**
     * Called BEFORE a sync-driven `player.seekTo(targetMs)`.
     * Arms [enableSync] AND arms the seek-suppression window with the target
     * position. The flag stays true until the window closes (position stabilizes
     * within [SeekSuppressionWindow.thresholdMs] of target, or timeout elapses).
     */
    fun armForSeek(targetMs: Long, nowMs: Long) {
        enableSync.value = true
        seekWindow.arm(targetMs, nowMs)
    }

    /**
     * Called by the `playbackState` collector on each emission.
     *
     * @return `true` if the outbound `State` (via `dispatcher.controlPlayback`)
     *   should be suppressed; `false` if it should be sent.
     *
     * When the seek window is NOT active, the flag resets after the first
     * suppressed emission (single state change for pause/play). When the seek
     * window IS active, the flag persists until the window closes (see
     * [shouldSuppressPositionOutbound]).
     */
    fun shouldSuppressPlaybackOutbound(): Boolean {
        if (!enableSync.value) return false
        if (seekWindow.isActive) return true
        // No seek window active — reset after suppressing one state change.
        enableSync.value = false
        return true
    }

    /**
     * Called by the `currentPositionMillis` collector on each emission.
     *
     * @return `true` if the outbound seek (via `dispatcher.sendSeek`) should be
     *   suppressed; `false` if it should be sent.
     *
     * When the seek window closes (position stabilizes or timeout), resets
     * [enableSync] so subsequent emissions are broadcast normally.
     */
    fun shouldSuppressPositionOutbound(currentPositionMs: Long, nowMs: Long): Boolean {
        if (!enableSync.value) return false
        if (seekWindow.shouldClose(currentPositionMs, nowMs)) {
            enableSync.value = false
            return false
        }
        return true
    }

    /**
     * Called by the media-load collector. Returns `true` if the outbound
     * `Set.file` announce should be suppressed (sync-driven switchEpisode).
     * Resets [enableFileSync] after the first suppressed media-load event.
     */
    fun shouldSuppressFileOutbound(): Boolean {
        if (!enableFileSync.value) return false
        enableFileSync.value = false
        return true
    }

    /**
     * Called BEFORE a sync-driven `switchEpisode(episodeId)`.
     * Arms [enableFileSync] so the resulting media-load emission is suppressed
     * from the outbound `Set.file` announce.
     */
    fun armForFileSwitch() {
        enableFileSync.value = true
    }
}

/**
 * Seek-suppression window for the player bridge.
 *
 * A `seekTo()` may cause `currentPositionMillis` to emit MULTIPLE times as the
 * position settles. This window tracks whether suppression should continue:
 *
 * - Closes (returns `true` from [shouldClose]) when the position is within
 *   [thresholdMs] of the target for one tick (stabilized), OR when [timeoutMs]
 *   elapses since [arm] (safety timeout).
 * - While open (active), the bridge suppresses all outbound `State` packets
 *   that would echo the sync-driven seek.
 */
class SeekSuppressionWindow(
    private val thresholdMs: Long = SEEK_THRESHOLD_MS,
    private val timeoutMs: Long = SEEK_TIMEOUT_MS,
) {
    private var targetMs: Long? = null
    private var armedAtMs: Long = 0L

    @Volatile
    private var closed: Boolean = true

    /** `true` while the window is open (suppression active). */
    val isActive: Boolean get() = !closed && targetMs != null

    /**
     * Arms the window with the seek target position.
     *
     * @param targetMs the position the player was seeked to, in milliseconds.
     * @param nowMs the current wall-clock time in milliseconds.
     */
    fun arm(targetMs: Long, nowMs: Long) {
        this.targetMs = targetMs
        this.armedAtMs = nowMs
        this.closed = false
    }

    /**
     * Checks whether the window should close (suppression should end).
     *
     * @param currentPositionMs the player's current position in milliseconds.
     * @param nowMs the current wall-clock time in milliseconds.
     * @return `true` if the window has closed (position stabilized or timeout),
     *   `false` if suppression should continue.
     */
    fun shouldClose(currentPositionMs: Long, nowMs: Long): Boolean {
        if (closed) return true
        val target = targetMs ?: return true
        if (abs(currentPositionMs - target) <= thresholdMs) {
            closed = true
            return true
        }
        if (nowMs - armedAtMs >= timeoutMs) {
            closed = true
            return true
        }
        return false
    }

    /** Resets the window to the closed state. */
    fun reset() {
        closed = true
        targetMs = null
    }
}

/**
 * Parses identity-in-filename: `${subjectId}[${episodeId}]` format.
 *
 * Used to encode the animeko episode identity in the Syncplay `Set.file.name`
 * field so animeko peers can auto-switch episodes. Non-matching filenames
 * (e.g. from Kazumi peers using `bangumiId[episodeNumber]` with different
 * semantics, or any non-animeko client) are gracefully ignored — returns `null`.
 *
 * @return `Pair(subjectId, episodeId)>` if the filename matches, `null` otherwise.
 */
fun parseIdentityInFilename(filename: String): Pair<Int, Int>? {
    val regex = Regex("""(\d+)\[(\d+)]""")
    val match = regex.find(filename) ?: return null
    val subjectId = match.groupValues[1].toIntOrNull() ?: return null
    val episodeId = match.groupValues[2].toIntOrNull() ?: return null
    return subjectId to episodeId
}

/**
 * Encodes identity-in-filename: `${subjectId}[${episodeId}]`.
 *
 * The encoded string is placed in `Set.file.name` on media load so animeko
 * peers can parse it and auto-switch episodes.
 */
fun encodeIdentityInFilename(subjectId: Int, episodeId: Int): String {
    return "${subjectId}[${episodeId}]"
}

/**
 * Decides whether an inbound file change should trigger an episode switch.
 *
 * Anti-loop guard: if the parsed episodeId equals the current episodeId,
 * no switch is needed (the peer is playing the same episode). This mirrors
 * Kazumi's `episode != currentEpisode()` guard.
 *
 * @return `true` if `switchEpisode(episodeId)` should be called, `false` otherwise.
 */
fun shouldSwitchEpisode(parsedEpisodeId: Int, currentEpisodeId: Int): Boolean {
    return parsedEpisodeId != currentEpisodeId
}

private const val SEEK_THRESHOLD_MS = ProtocolManager.SEEK_THRESHOLD * 1000L
private const val SEEK_TIMEOUT_MS = 2000L
