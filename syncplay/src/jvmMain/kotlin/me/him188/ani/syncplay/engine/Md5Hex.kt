package me.him188.ani.syncplay.engine

import java.security.MessageDigest

/**
 * JVM `actual` for [md5Hex]: `MessageDigest.getInstance("MD5")` over UTF-8 bytes,
 * then `ByteArray.toHexString(HexFormat.Default)` for lowercase hex output.
 *
 * `HexFormat.Default` (lowercase) matches the syncplay protocol's expected MD5 hex
 * representation and the reference client's `md5(it).toHexString(HexFormat.Default)`.
 */
actual fun md5Hex(input: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.toHexString(HexFormat.Default)
}
