package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class MongoTimeExtensionsTest {
    @Test
    fun `epoch microseconds preserve positive and negative fractional instants`() {
        assertThat(Instant.parse("1970-01-01T00:00:00.123456789Z").toEpochMicroseconds()).isEqualTo(123456L)
        assertThat(Instant.parse("1969-12-31T23:59:59.123456789Z").toEpochMicroseconds()).isEqualTo(-876544L)
        assertThat(Instant.EPOCH.toEpochMicroseconds()).isZero()
    }

    @Test
    fun `the full signed microsecond range is representable`() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE).forEach { micros ->
            val instant =
                Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
            assertThat(instant.toEpochMicroseconds()).isEqualTo(micros)
        }
    }

    @Test
    fun `timestamps outside the microsecond range fail instead of wrapping`() {
        listOf(Instant.MIN, Instant.MAX).forEach { instant ->
            assertThatThrownBy { instant.toEpochMicroseconds() }.isInstanceOf(ArithmeticException::class.java)
        }
    }
}
