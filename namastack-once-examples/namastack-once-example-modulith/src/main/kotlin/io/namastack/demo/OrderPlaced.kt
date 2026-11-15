package io.namastack.demo

import java.util.UUID

data class OrderPlaced(
    val eventId: UUID,
    val orderId: UUID,
    val amountCents: Long,
)
