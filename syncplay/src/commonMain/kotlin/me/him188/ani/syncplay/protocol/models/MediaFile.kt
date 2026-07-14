package me.him188.ani.syncplay.protocol.models

/**
 * Minimal protocol-level file descriptor: the file metadata that the syncplay protocol
 * exchanges about a user's current file (name, size, duration).
 *
 * This is a self-contained port of syncplay-mobile's `app.player.models.MediaFile`,
 * stripped of the player/UI concerns (Compose `SnapshotStateList` tracks/chapters,
 * filekit `PlatformFile` location, resource lookups) that don't belong in the protocol
 * layer. The three fields here are exactly the subset that the client-side
 * `MediaFile.toFileData()` conversion reads to build the wire-level `FileData`, so a
 * later room-layer port can reconstruct the conversion against this type unchanged.
 */
data class MediaFile(
    val fileName: String = "",
    val fileSize: String = "",
    val fileDuration: Double? = null,
)
