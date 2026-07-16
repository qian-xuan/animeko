/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.episode.UnsafeEpisodeSessionApi
import me.him188.ani.syncplay.engine.BridgeAntiLoop
import me.him188.ani.syncplay.engine.Playback
import me.him188.ani.syncplay.engine.ProtocolManager
import me.him188.ani.syncplay.engine.RoomCallback
import me.him188.ani.syncplay.engine.SyncplayController
import me.him188.ani.syncplay.engine.parseIdentityInFilename
import me.him188.ani.syncplay.engine.shouldSwitchEpisode
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin
import org.openani.mediamp.isPlaying
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Syncplay player extension. Bridges the animeko player with the [SyncplayController].
 *
 * The extension instance is created once and persists across episodes. [onStart] is called
 * once per [EpisodeSession] (per-episode); the old session's [ExtensionBackgroundTaskScope]
 * is cancelled on switch.
 *
 * The bridge is bidirectional:
 * - **Outbound**: player playback state and position are collected and sent to the server
 *   via [me.him188.ani.syncplay.engine.RoomEventDispatcher.controlPlayback] and
 *   [me.him188.ani.syncplay.engine.RoomEventDispatcher.sendSeek]. The player's play state
 *   also feeds [SyncplayController.isPlayingFlow] for the health monitor's
 *   playback-broadcast coroutine.
 * - **Inbound**: the extension installs itself as the controller's
 *   [SyncplayController.playerBridge] ([RoomCallback]) so inbound pause/play/seek events
 *   drive the player, and collects [SyncplayController.inboundFileFlow] to auto-switch
 *   episodes when a peer loads a file with a matching identity-in-filename.
 *
 * Anti-loop logic ([BridgeAntiLoop]) prevents feedback loops: sync-driven player calls
 * arm suppression flags so the resulting player-state emissions are not re-broadcast.
 */
class SyncplayPlayerExtension private constructor(
    private val context: PlayerExtensionContext,
    private val controller: SyncplayController,
) : PlayerExtension("SyncplayPlayerExtension"), RoomCallback {

    private val antiLoop = BridgeAntiLoop()

    @OptIn(UnsafeEpisodeSessionApi::class)
    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope,
    ) {
        logger.info { "SyncplayPlayerExtension started for episode ${episodeSession.episodeId}" }

        // Install this extension as the controller's player-driving callback so inbound
        // room events (pause/play/seek) reach the player.
        controller.playerBridge = this

        val player = context.player

        // 1. Outbound: player playbackState -> controller.isPlayingFlow + controlPlayback.
        //    Feeds isPlayingFlow UNCONDITIONALLY (not gated by enableSync) so the health
        //    monitor sees the real player state. On a pause/play transition, sends a
        //    State packet unless the change was sync-driven (anti-loop suppresses it),
        //    AND only when the controller is actually connected to a server.
        //    Also updates controller.playerPositionMs and controller.mediaLoaded so the
        //    engine's decideAction can use the real local position and media state.
        backgroundTaskScope.launch("PlaybackStateBridge") {
            var wasPlaying = player.playbackState.value.isPlaying
            player.playbackState.collect { state ->
                val isPlaying = state.isPlaying
                controller.isPlayingFlow.value = isPlaying
                controller.mediaLoaded = true

                if (isPlaying != wasPlaying) {
                    if (!antiLoop.shouldSuppressPlaybackOutbound()
                        && controller.state.value == me.him188.ani.syncplay.protocol.models.ConnectionState.CONNECTED
                    ) {
                        val playback = if (isPlaying) Playback.PLAY else Playback.PAUSE
                        controller.dispatcher.controlPlayback(playback)
                    }
                    wasPlaying = isPlaying
                }
            }
        }

        // 2. Outbound: player position -> seek detection -> sendSeek.
        //    A position jump larger than [ProtocolManager.SEEK_THRESHOLD] (after the player
        //    has started) is treated as a user-initiated seek and broadcast to the room.
        //    Sync-driven seeks are suppressed via the seek-suppression window.
        //    Only active when connected to a server.
        //    Also updates controller.playerPositionMs so the engine's decideAction can
        //    compute the real diff between local and global position.
        backgroundTaskScope.launch("PositionBridge") {
            var lastPosMs = 0L
            var wasSuppressing = false
            player.currentPositionMillis.collect { pos ->
                controller.playerPositionMs = pos
                val nowMs = Clock.System.now().toEpochMilliseconds()
                if (antiLoop.shouldSuppressPositionOutbound(pos, nowMs)) {
                    lastPosMs = pos
                    wasSuppressing = true
                    return@collect
                }
                // If the suppression window just closed, skip the delta check for this
                // one emission — the position jump is from the sync-driven seek, not a
                // user action. Without this, the huge delta triggers a spurious sendSeek.
                if (wasSuppressing) {
                    lastPosMs = pos
                    wasSuppressing = false
                    return@collect
                }
                if (controller.state.value != me.him188.ani.syncplay.protocol.models.ConnectionState.CONNECTED) {
                    lastPosMs = pos
                    return@collect
                }
                val delta = abs(pos - lastPosMs)
                if (lastPosMs > 0 && delta > ProtocolManager.SEEK_THRESHOLD * 1000L) {
                    controller.dispatcher.sendSeek(pos)
                }
                lastPosMs = pos
            }
        }

        // 3. Inbound: Set.file -> switchEpisode with anti-loop.
        //    Parses identity-in-filename (`subjectId[episodeId]`) from a peer's Set.file
        //    and switches to the matching episode. Arms enableFileSync so the resulting
        //    media-load emission does not echo back as an outbound Set.file announce.
        backgroundTaskScope.launch("InboundFileBridge") {
            controller.inboundFileFlow.collect { file ->
                val filename = file?.name ?: return@collect
                val parsed = parseIdentityInFilename(filename) ?: return@collect
                val (_, episodeId) = parsed
                val currentEpisodeId = context.getCurrentEpisodeId()
                if (shouldSwitchEpisode(episodeId, currentEpisodeId)) {
                    antiLoop.armForFileSwitch()
                    context.switchEpisode(episodeId)
                }
            }
        }
    }

    override suspend fun onBeforeSwitchEpisode(newEpisodeId: Int) {
        controller.protocol.markAwaitingRoomResync()
    }

    override suspend fun onClose() {
        // Detach the player-driving callback. The backgroundTaskScope is cancelled
        // automatically by the framework, which tears down the bridge coroutines.
        controller.playerBridge = null
    }

    // -- RoomCallback: inbound player-driving callbacks --
    // Each arms the anti-loop BEFORE the player call so the resulting playbackState /
    // currentPositionMillis emission is suppressed from outbound broadcast.

    override suspend fun onSomeonePaused(setBy: String) {
        antiLoop.armForPlaybackChange()
        withContext(Dispatchers.Main.immediate) {
            context.player.pause()
        }
    }

    override suspend fun onSomeonePlayed(setBy: String) {
        antiLoop.armForPlaybackChange()
        withContext(Dispatchers.Main.immediate) {
            context.player.resume()
        }
    }

    override suspend fun onSomeoneSeeked(setBy: String, positionSec: Double) {
        val targetMs = (positionSec * 1000).toLong()
        val nowMs = Clock.System.now().toEpochMilliseconds()
        antiLoop.armForSeek(targetMs, nowMs)
        withContext(Dispatchers.Main.immediate) {
            context.player.seekTo(targetMs)
        }
    }

    companion object : EpisodePlayerExtensionFactory<SyncplayPlayerExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): SyncplayPlayerExtension {
            return SyncplayPlayerExtension(context, koin.get<SyncplayController>())
        }

        private val logger = logger<SyncplayPlayerExtension>()
    }
}
