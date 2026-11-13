package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdempotencyKeyHasherTest {
    private val hasher = IdempotencyKeyHasher()

    @Test
    fun `framing prevents concatenation ambiguity and preserves exact string contents`() {
        assertThat(hasher.hash("ab", "c")).isNotEqualTo(hasher.hash("a", "bc"))
        assertThat(hasher.hash("op", " key")).isNotEqualTo(hasher.hash("op", "key"))
        assertThat(hasher.hash("op", "A")).isNotEqualTo(hasher.hash("op", "a"))
    }

    @Test
    fun `identity matches a fixed UTF-8 length-framed persistence vector`() {
        assertThat(
            hasher.hash("invoice.create", "ä/123"),
        ).isEqualTo("a8ee7ac9b4ad3c16d67488f05481851d3c5166572716db8f522f79889a294f10")
    }
}
