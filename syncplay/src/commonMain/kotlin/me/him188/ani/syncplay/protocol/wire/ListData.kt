package me.him188.ani.syncplay.protocol.wire

import kotlinx.serialization.Serializable
import me.him188.ani.syncplay.protocol.models.LenientRoomFeaturesSerializer
import me.him188.ani.syncplay.protocol.models.RoomFeatures

/**
 * Per-user payload inside a server `List` response.
 *
 * @property position Last reported playback position (server-side, may be 0 for clients without media).
 * @property isReady Whether the user is marked as ready (null if readiness is disabled).
 * @property file File metadata if the user has a media loaded.
 * @property controller Whether the user has controller privileges in a managed room.
 * @property features Feature flags reported by that user.
 */
@Serializable
data class ListUserData(
    val position: Double? = null,
    val isReady: Boolean? = null,
    val file: FileData? = null,
    val controller: Boolean = false,
    @Serializable(with = LenientRoomFeaturesSerializer::class)
    val features: RoomFeatures? = null
)
