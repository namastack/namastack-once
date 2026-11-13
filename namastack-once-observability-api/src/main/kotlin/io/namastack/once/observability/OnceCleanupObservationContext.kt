package io.namastack.once.observability

import io.micrometer.observation.Observation
import java.time.Instant

/**
 * One independently committed cleanup batch.
 * @property backend Persistence adapter name.
 * @property cutoff Inclusive expiry cutoff.
 * @property batchSize Requested maximum deletions.
 * @property deletedCount Committed deletions, populated only after successful completion.
 * @property outcome Final result: success, error, or unknown before completion.
 * @since 0.1.0
 */
class OnceCleanupObservationContext(
    val backend: String,
    val cutoff: Instant,
    val batchSize: Int,
) : Observation.Context() {
    var deletedCount: Int = 0
    var outcome: String = "unknown"
}
