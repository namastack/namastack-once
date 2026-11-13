package io.namastack.once

import java.time.Instant

/**
 * Immutable reservation persisted in the same transaction as application changes.
 *
 * @property keyHash Length-framed scope/key SHA-256 digest; the raw key is never persisted.
 * @property scope Stable application scope, retained for operational inspection.
 * @property createdAt UTC reservation instant with microsecond precision.
 * @property expiresAt Optional earliest cleanup time; the marker protects its key until it is deleted.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
data class IdempotencyMarker(
    val keyHash: String,
    val scope: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
)
