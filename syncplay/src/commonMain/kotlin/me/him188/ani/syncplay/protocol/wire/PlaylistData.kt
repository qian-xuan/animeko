package me.him188.ani.syncplay.protocol.wire

import kotlinx.serialization.Serializable

/**
 * STUB — F14/F15 deferred.
 *
 * Placeholder for playlist-related wire payloads (`PlaylistChangeData`, `PlaylistIndexData`
 * in the reference syncplay-mobile source). Declared but NOT wired into any other DTO so
 * it is cheap to fill in when playlist support lands.
 */
@Serializable
data class PlaylistData(
    val placeholder: String? = null
)
