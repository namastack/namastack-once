package io.namastack.once

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Deterministic test clock, sampled by Once without real-time sleeps for expiry assertions. */
class MutableOnceClock(
    private var now: Instant = Instant.parse("2026-11-12T10:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
