package me.him188.ani.syncplay.protocol.wire

import kotlinx.serialization.Serializable
import me.him188.ani.syncplay.protocol.models.LenientRoomFeaturesSerializer
import me.him188.ani.syncplay.protocol.models.RoomFeatures

/**
 * Inner payload of a `Set` message — multi-purpose envelope. Each direction populates a
 * different subset of these fields:
 *
 * - Server->client: [user] broadcasts of joins/leaves/file changes, [ready] state,
 *   [features].
 * - Client->server: [room] for room change, [file] for setting own file, [ready] for own
 *   readiness, [features].
 *
 * v1 subset — the playlist and controlled-room fields (`controllerAuth`,
 * `newControlledRoom`, `playlistIndex`, `playlistChange`) are deferred to F14/F15 along
 * with their DTOs ([ControlledRoomData], [PlaylistData]).
 */
@Serializable
data class SetData(
    /** Server->client user broadcast: `{username -> UserSetData}`. */
    val user: Map<String, UserSetData>? = null,
    /** Client->server room change request. */
    val room: Room? = null,
    /** Client->server: own file metadata. */
    val file: FileData? = null,
    val ready: ReadyData? = null,
    @Serializable(with = LenientRoomFeaturesSerializer::class)
    val features: RoomFeatures? = null
)
