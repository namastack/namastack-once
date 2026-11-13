package io.namastack.once.retention

import java.time.Duration
import java.time.Instant
import java.util.Optional

/**
 * Makes new markers eligible for cleanup after [duration], measured from reservation time.
 *
 * The expiry is calculated for a new reservation. Existing markers continue to protect their
 * identities until cleanup deletes them, including after this duration has elapsed.
 * Once rejects durations whose expiry does not remain after creation at microsecond precision.
 *
 * @property duration Positive duration added to the reservation time.
 * @throws IllegalArgumentException if the duration is zero or negative.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@JvmRecord
data class DurationIdempotencyRetentionPolicy(
    val duration: Duration,
) : IdempotencyRetentionPolicy {
    init {
        require(!duration.isZero && !duration.isNegative) { "Retention duration must be positive" }
    }

    /**
     * Adds this policy's retention duration to the supplied reservation time.
     *
     * @param context Invocation metadata; this fixed-duration policy does not vary by scope.
     * @param createdAt Reservation time supplied by the execution service.
     * @return The earliest instant at which cleanup may delete the marker.
     * @throws java.time.DateTimeException if the expiry exceeds the Instant range.
     * @throws ArithmeticException if adding the duration overflows.
     */
    override fun calculateExpiry(
        context: IdempotencyRetentionContext,
        createdAt: Instant,
    ): Optional<Instant> = Optional.of(createdAt.plus(duration))
}
