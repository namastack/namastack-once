package io.namastack.once.retention

import java.time.Instant
import java.util.Optional

/**
 * Determines when a newly reserved marker becomes eligible for cleanup.
 *
 * Retention controls deletion, not the execution decision. Every existing marker protects its
 * identity, including after this threshold. The action may run again only after the marker is
 * deleted. Delayed or disabled cleanup therefore extends duplicate protection.
 *
 * Policies affect new reservations only. Their duration should cover the application's expected
 * retry and replay window; a duplicate invocation never refreshes the stored retention threshold.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
fun interface IdempotencyRetentionPolicy {
    /**
     * Calculates the earliest cleanup time for a new reservation.
     *
     * The creation time is sampled once by Once. An empty result retains the marker indefinitely;
     * a finite cleanup threshold must remain after creation at database microsecond precision.
     *
     * @param context Scope metadata, excluding raw keys and application payloads.
     * @param createdAt Reservation time measured by the configured clock, before transaction commit.
     * @return The earliest cleanup instant, or an empty Optional for unlimited retention.
     */
    fun calculateExpiry(
        context: IdempotencyRetentionContext,
        createdAt: Instant,
    ): Optional<Instant>
}
