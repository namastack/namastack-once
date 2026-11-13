package io.namastack.once.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Marker cleanup settings; existing identities remain protected until their markers are deleted.
 *
 * Spring binds these immutable settings through the constructor, which validates configured values.
 *
 * @property enabled Whether to schedule background marker cleanup.
 * @property interval Fixed delay after each complete cleanup pass, at least one millisecond; defaults to one hour.
 * @property batchSize Per-transaction deletion limit from 1 to 100000; defaults to 1000 and repeats until a batch is empty.
 * @property shutdownTimeout Non-negative wait for an active cleanup pass to finish; defaults to 30 seconds.
 * @throws IllegalArgumentException if the interval, batch size, or shutdown timeout is invalid.
 * @throws ArithmeticException if the interval cannot be represented as a millisecond count.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties("namastack.once.cleanup", ignoreUnknownFields = false)
data class OnceCleanupProperties(
    val enabled: Boolean = true,
    val interval: Duration = Duration.ofHours(1),
    val batchSize: Int = 1000,
    val shutdownTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(interval.toMillis() > 0) { "Once cleanup interval must be at least one millisecond" }
        require(!shutdownTimeout.isNegative) { "Once cleanup shutdown timeout must not be negative" }
        require(batchSize in 1..100_000) { "Once cleanup batch size must be between 1 and 100000" }
    }
}
