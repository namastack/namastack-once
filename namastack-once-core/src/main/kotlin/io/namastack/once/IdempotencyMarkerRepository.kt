package io.namastack.once

import java.time.Instant

/**
 * Persistence boundary for transactional marker reservations and cleanup.
 *
 * A duplicate reservation may leave the transaction unusable; Once immediately rolls it back.
 * Every existing committed marker protects its identity until cleanup or explicit maintenance deletes it.
 * Reservations use the transaction owned by Once; cleanup batches commit independently.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
interface IdempotencyMarkerRepository {
    /**
     * Identifies the Spring-bound transaction resource containing this repository's markers.
     *
     * Core checks that the selected transaction manager binds this resource before reservation.
     * Implementations provide the canonical binding key; applications must ensure that business
     * writes also use this resource and transaction.
     */
    val transactionResourceKey: Any

    /**
     * Acquires an identity for application work within the transaction owned by Once.
     *
     * Only absent identities permit execution. Retention controls cleanup eligibility and does not
     * permit an invocation to replace a marker that is still present.
     *
     * @param marker Identity, reservation time, and retention window to persist atomically with business writes.
     * @return True when reserved; false for an existing identity, requiring an immediate transaction rollback.
     */
    fun reserve(marker: IdempotencyMarker): Boolean

    /**
     * Deletes one bounded batch of cleanup-eligible markers in an independent transaction.
     *
     * Only committed markers whose expiry is at or before the cutoff may be removed. Markers
     * without an expiry remain protected. The count is returned after commit; deletion and commit
     * failures propagate to the caller instead of reporting successful cleanup.
     *
     * @param cutoff Latest expiry eligible for deletion, inclusive.
     * @param batchSize Maximum markers deleted by this call.
     * @return Number of committed deletions, or zero when no eligible markers remain.
     */
    fun cleanup(
        cutoff: Instant,
        batchSize: Int,
    ): Int
}
