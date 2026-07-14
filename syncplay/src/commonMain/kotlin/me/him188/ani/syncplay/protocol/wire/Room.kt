package me.him188.ani.syncplay.protocol.wire

import kotlinx.serialization.Serializable

/** Room reference: `{"name": "..."}`. Used by both directions. */
@Serializable
data class Room(val name: String)
