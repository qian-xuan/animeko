package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineScope
import me.him188.ani.syncplay.network.KtorSyncplayNetworkManager
import me.him188.ani.syncplay.network.SyncplayNetworkManager

/**
 * JVM `actual` for [createSyncplayNetworkManager]: returns a [KtorSyncplayNetworkManager]
 * over `ktor-network` TCP sockets.
 *
 * Inherited by `androidMain` and `desktopMain` via the KMP source set hierarchy — no
 * per-target actual is needed.
 */
actual fun createSyncplayNetworkManager(scope: CoroutineScope): SyncplayNetworkManager {
    return KtorSyncplayNetworkManager(scope)
}
