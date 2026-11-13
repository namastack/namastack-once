package io.namastack.once.retention

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class IdempotencyRetentionPolicyTest {
    @Test
    fun `duration policy measures expiry from reservation`() {
        val now = Instant.parse("2026-11-12T10:00:00Z")
        val context = IdempotencyRetentionContext("order.create")
        assertThat(DurationIdempotencyRetentionPolicy(Duration.ofDays(2)).calculateExpiry(context, now))
            .contains(now.plus(Duration.ofDays(2)))
        assertThat(ForeverIdempotencyRetentionPolicy.calculateExpiry(context, now)).isEmpty()
    }

    @Test
    fun `finite policies reject zero and negative durations`() {
        assertThatIllegalArgumentException().isThrownBy { DurationIdempotencyRetentionPolicy(Duration.ZERO) }
        assertThatIllegalArgumentException().isThrownBy { DurationIdempotencyRetentionPolicy(Duration.ofSeconds(-1)) }
    }
}
