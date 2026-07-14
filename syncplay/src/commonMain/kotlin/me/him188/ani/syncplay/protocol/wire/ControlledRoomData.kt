package me.him188.ani.syncplay.protocol.wire

import kotlinx.serialization.Serializable

/**
 * STUB — F14/F15 deferred.
 *
 * Placeholder for controlled-room wire payloads (`ControllerAuthData`, `NewControlledRoom`
 * in the reference syncplay-mobile source). Declared but NOT wired into any other DTO so
 * it is cheap to fill in when managed-room support lands.
 */
@Serializable
data class ControlledRoomData(
    val placeholder: String? = null
)
