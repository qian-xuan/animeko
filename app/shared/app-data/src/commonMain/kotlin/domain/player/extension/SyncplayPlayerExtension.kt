/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.syncplay.engine.SyncplayController
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

/**
 * Syncplay player extension. Bridges the animeko player with the [SyncplayController].
 *
 * The extension instance is created once and persists across episodes. [onStart] is called
 * once per [EpisodeSession] (per-episode); the old session's [ExtensionBackgroundTaskScope]
 * is cancelled on switch.
 *
 * T4.2: Skeleton only — logs start, marks awaiting-room-resync on episode switch.
 * T4.3 will fill in the full bidirectional bridge (player state -> outbound State packets,
 * inbound Set.file -> switchEpisode, player playback -> controller.isPlayingFlow) and
 * anti-loop logic.
 */
class SyncplayPlayerExtension private constructor(
    private val controller: SyncplayController,
) : PlayerExtension("SyncplayPlayerExtension") {

    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope,
    ) {
        logger.info { "SyncplayPlayerExtension started for episode ${episodeSession.episodeId}" }
        // T4.3: Launch bridge coroutines here using episodeSession directly.
        // The bridge will:
        // 1. Collect player.playbackState + currentPositionMillis -> outbound State
        // 2. Collect controller's inbound Set.file -> switchEpisode
        // 3. Feed player.playbackState -> controller.isPlayingFlow
    }

    override suspend fun onBeforeSwitchEpisode(newEpisodeId: Int) {
        controller.protocol.markAwaitingRoomResync()
    }

    override suspend fun onClose() {
        // T4.3: Cancel any bridge coroutines.
        // For now, the backgroundTaskScope handles cancellation.
    }

    companion object : EpisodePlayerExtensionFactory<SyncplayPlayerExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): SyncplayPlayerExtension {
            return SyncplayPlayerExtension(koin.get<SyncplayController>())
        }

        private val logger = logger<SyncplayPlayerExtension>()
    }
}
