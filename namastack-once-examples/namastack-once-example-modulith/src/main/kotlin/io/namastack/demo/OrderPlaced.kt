package io.namastack.demo

import java.util.UUID

/**
 * Stable event identity reused when Modulith resubmits a failed or repeated publication.
 *
 * @property eventId Identity used by Once independently of the publication registry's record ID.
 * @property orderId Business aggregate associated with the event.
 * @property amountCents Amount copied to the invoice by the receiving listener.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
data class OrderPlaced(
    val eventId: UUID,
    val orderId: UUID,
    val amountCents: Long,
)
