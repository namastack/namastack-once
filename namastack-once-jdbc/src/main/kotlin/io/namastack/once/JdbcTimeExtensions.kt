package io.namastack.once

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC

/**
 * Converts an instant to UTC [LocalDateTime] fields for JDBC `TIMESTAMP` binding.
 *
 * Uses UTC rather than the JVM's default timezone. Nullable timestamps are handled by the caller.
 */
internal fun Instant.toUtcTimestamp(): LocalDateTime = LocalDateTime.ofInstant(this, UTC)
