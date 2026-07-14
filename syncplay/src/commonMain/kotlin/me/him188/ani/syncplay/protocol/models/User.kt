package me.him188.ani.syncplay.protocol.models

/**
 * One user in the current room, as modelled from the server's `List`/`Set` payloads.
 *
 * Ported from syncplay-mobile `app.protocol.models.User`. The `file` field references
 * [MediaFile] (a minimal protocol-level descriptor) rather than the player-side
 * `MediaFile` (which carries Compose/filekit state that doesn't belong in the protocol
 * layer).
 */
data class User(
    var index: Int = 0, // server-assigned index; 0 for the current user
    var name: String = "",
    var readiness: Boolean, // whether the user marked themselves ready
    var file: MediaFile?, // file the user is playing, null if none
    var isController: Boolean, // whether the user controls the managed room
)
