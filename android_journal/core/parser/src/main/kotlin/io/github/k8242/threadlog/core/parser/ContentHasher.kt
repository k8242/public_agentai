// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import java.security.MessageDigest

/**
 * Pure-JVM SHA-256 hashing utilities producing lowercase 64-character hexadecimal digests.
 */
object ContentHasher {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val value = digest[i].toInt() and 0xFF
            chars[i * 2] = HEX_CHARS[value ushr 4]
            chars[i * 2 + 1] = HEX_CHARS[value and 0x0F]
        }
        return String(chars)
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
}
