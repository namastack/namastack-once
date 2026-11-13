package io.namastack.once

import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import io.namastack.once.retention.ForeverIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Duration

class IdempotencyRequestTest {
    @Test
    fun `request options preserve the other immutable options`() {
        val original = IdempotencyRequest.of("order.create", "request")
        val policy = DurationIdempotencyRetentionPolicy(Duration.ofDays(2))
        val selected = original.withRetentionPolicy(policy)
        assertThat(original.retentionPolicy).isSameAs(ForeverIdempotencyRetentionPolicy)
        assertThat(selected.scope).isEqualTo(original.scope)
        assertThat(selected.key).isEqualTo(original.key)
        assertThat(selected.retentionPolicy).isSameAs(policy)
    }

    @Test
    fun `invalid scopes fail before invocation`() {
        assertThatIllegalArgumentException().isThrownBy { IdempotencyRequest.of(" ", "key") }
        assertThatIllegalArgumentException().isThrownBy { IdempotencyRequest.of("x".repeat(201), "key") }
    }
}
