package me.him188.ani.syncplay.engine

/**
 * Computes the MD5 hash of [input] (UTF-8 bytes) and returns it as a lowercase hex string.
 *
 * Used by the Hello handshake to hash the room password before sending it to the server.
 * Platform-specific because `java.security.MessageDigest` is JVM-only; the `commonMain`
 * `expect` is resolved by `jvmMain` (and inherited by `androidMain` / `desktopMain`).
 */
expect fun md5Hex(input: String): String
