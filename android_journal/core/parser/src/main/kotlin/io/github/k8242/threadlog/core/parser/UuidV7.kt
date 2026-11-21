// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/**
 * RFC 9562 time-sortable UUID version 7 generator.
 *
 * Layout:
 * - 48 bits: unsigned big-endian Unix timestamp in milliseconds
 * - 4 bits: version (`0b0111`)
 * - 12 bits: random (`rand_a`)
 * - 2 bits: RFC 9562 variant (`0b10`)
 * - 62 bits: random (`rand_b`)
 */
object UuidV7 {
    private val defaultRandom = SecureRandom()

    /** Generates a lowercase canonical RFC 9562 UUID v7 string. */
    fun generate(
        timestampMs: Long = System.currentTimeMillis(),
        random: Random = defaultRandom,
    ): String = generateUuid(timestampMs, random).toString()

    /** Generates a [UUID] instance conforming to RFC 9562 version 7. */
    fun generateUuid(
        timestampMs: Long = System.currentTimeMillis(),
        random: Random = defaultRandom,
    ): UUID {
        require(timestampMs >= 0L) { "timestampMs must be non-negative, got $timestampMs" }

        val ts48 = timestampMs and 0xFFFF_FFFF_FFFFL
        val randA = random.nextInt(1 shl 12).toLong() and 0x0FFFL
        val msb = (ts48 shl 16) or (0x7L shl 12) or randA

        val randB = random.nextLong()
        val lsb = (randB and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE

        return UUID(msb, lsb)
    }

    /** Extracts the 48-bit Unix epoch millisecond timestamp embedded in a UUID v7 string. */
    fun extractTimestampMs(uuid: String): Long =
        UUID.fromString(uuid).mostSignificantBits ushr 16
}
