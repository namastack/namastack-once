package io.namastack.once

import java.time.Instant
import java.time.temporal.ChronoUnit.MICROS

/**
 * Applies Once's microsecond precision to marker timestamps and cleanup cutoffs.
 *
 * @return This instant truncated to microseconds.
 */
internal fun Instant.toMarkerPrecision(): Instant = truncatedTo(MICROS)
