package me.him188.ani.syncplay.engine

import kotlinx.coroutines.CoroutineScope
import me.him188.ani.syncplay.network.SyncplayNetworkManager

/**
 * Platform-specific factory for creating the concrete [SyncplayNetworkManager] subclass.
 *
 * JVM/Android/Desktop: returns `KtorSyncplayNetworkManager` (ktor-network TCP sockets).
 * iOS: not supported in v1 — the `iosMain` actual is deferred until iOS is enabled.
 *
 * Mirrors the `createMeteredNetworkDetector` expect/actual pattern: the `commonMain`
 * `expect` is resolved by `jvmMain` (inherited by `androidMain` / `desktopMain`), so a
 * single JVM actual covers every target when iOS is disabled (the default).
 */
expect fun createSyncplayNetworkManager(scope: CoroutineScope): SyncplayNetworkManager
