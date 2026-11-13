package io.namastack.once

import io.namastack.once.IdempotencyResult.Duplicate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IdempotencyResultTest {
    @Test
    fun `fold handles a fresh value and a duplicate without casts`() {
        val fresh: IdempotencyResult<String> = IdempotencyResult.executed("created")
        val duplicate: IdempotencyResult<String> =
            Duplicate(IdempotencyDuplicateContext("op", "key", "hash"))
        assertThat(fresh.fold({ it }, { "duplicate" })).isEqualTo("created")
        assertThat(duplicate.fold({ it }, { "duplicate" })).isEqualTo("duplicate")
        assertThat(fresh.wasExecuted()).isTrue()
        assertThat(duplicate.wasExecuted()).isFalse()
    }

    @Test
    fun `no-value success carries no duplicate metadata`() {
        val result = IdempotencyResult.executed()
        assertThat(result.value).isNull()
        assertThat(result.wasExecuted()).isTrue()
    }
}
