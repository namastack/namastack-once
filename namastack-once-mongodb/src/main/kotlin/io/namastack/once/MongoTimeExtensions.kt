package io.namastack.once

import java.time.Instant

/**
 * Encodes an instant as BSON-compatible epoch microseconds without losing precision to BSON dates.
 *
 * Negative fractional instants are adjusted before multiplication to preserve the full Long range.
 *
 * @return The epoch microsecond count, truncating any smaller fractional part.
 * @throws ArithmeticException if the epoch microsecond count cannot fit in a Long.
 */
internal fun Instant.toEpochMicroseconds(): Long {
    val microsPerSecond = 1_000_000L
    val fractionalMicros = nano / 1_000L
    return if (epochSecond < 0 && nano > 0) {
        Math.addExact(Math.multiplyExact(epochSecond + 1, microsPerSecond), fractionalMicros - microsPerSecond)
    } else {
        Math.addExact(Math.multiplyExact(epochSecond, microsPerSecond), fractionalMicros)
    }
}
