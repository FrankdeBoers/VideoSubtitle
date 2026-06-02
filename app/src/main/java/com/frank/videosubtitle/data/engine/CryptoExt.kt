package com.frank.videosubtitle.data.engine

import java.security.MessageDigest

/**
 * Shared hex/digest helpers for the translation engines (Tencent TC3-HMAC,
 * Baidu MD5 sign, Youdao SHA-256 sign). Kept in `data.engine` because they are
 * implementation detail of the cloud translators — no other layer needs them.
 */
private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHexLower(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(HEX[(b.toInt() ushr 4) and 0x0f])
        sb.append(HEX[b.toInt() and 0x0f])
    }
    return sb.toString()
}

internal fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .toHexLower()

internal fun md5Hex(input: String): String =
    MessageDigest.getInstance("MD5")
        .digest(input.toByteArray(Charsets.UTF_8))
        .toHexLower()
