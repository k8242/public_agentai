package com.threadlog.core.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random
import java.util.UUID

class UuidV7AndContentHasherTest {

    @Test
    fun uuidV7_hasValidVersion7AndRfc9562VariantBitsAndEmbeddedTimestamp() {
        val ts = 1_763_568_900_123L
        val generated = UuidV7.generate(timestampMs = ts, random = Random(42L))
        val parsed = UUID.fromString(generated)

        assertThat(parsed.version()).isEqualTo(7)
        assertThat(parsed.variant()).isEqualTo(2)
        assertThat(UuidV7.extractTimestampMs(generated)).isEqualTo(ts)
        assertThat(generated).isEqualTo(generated.lowercase())
    }

    @Test
    fun uuidV7_isLexicographicallyMonotonicAcrossIncreasingTimestamps() {
        val timestamps = listOf(
            0L,
            1L,
            1_000L,
            1_700_000_000_000L,
            1_700_000_000_001L,
            1_763_568_900_000L,
            2_000_000_000_000L,
        )

        val uuids = timestamps.mapIndexed { idx, ts ->
            UuidV7.generate(timestampMs = ts, random = Random(idx.toLong()))
        }

        assertThat(uuids).isInStrictOrder()
    }

    @Test
    fun uuidV7_defaultGeneratorProducesUniqueV7Strings() {
        val first = UuidV7.generate()
        val second = UuidV7.generate()

        assertThat(first).isNotEqualTo(second)
        assertThat(UUID.fromString(first).version()).isEqualTo(7)
        assertThat(UUID.fromString(second).version()).isEqualTo(7)
    }

    @Test
    fun contentHasher_matchesKnownSha256Vectors() {
        assertThat(ContentHasher.sha256Hex(""))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(ContentHasher.sha256Hex("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(ContentHasher.sha256Hex("hello world".toByteArray(Charsets.UTF_8)))
            .isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9")
    }
}
