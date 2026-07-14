package me.him188.ani.syncplay.protocol.wire

import kotlinx.serialization.Serializable
import me.him188.ani.syncplay.protocol.models.LenientRoomFeaturesSerializer
import me.him188.ani.syncplay.protocol.models.RoomFeatures

/**
 * Inner payload of a `Hello` message — handshake exchanged in both directions.
 *
 * Direction-specific fields:
 * - [password] — pre-hashed (MD5) server password. Set by client only.
 * - [motd] — server's message of the day. Set by server only.
 *
 * All other fields are common: [username], [room], [version]/[realversion], [features].
 */
@Serializable
data class HelloData(
    val username: String? = null,
    val password: String? = null,
    val room: Room? = null,
    val version: String? = null,
    val realversion: String? = null,
    @Serializable(with = LenientRoomFeaturesSerializer::class)
    val features: RoomFeatures = RoomFeatures(),
    val motd: String? = null
)
