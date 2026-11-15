package io.namastack.demo

import java.util.UUID

@JvmRecord
data class OrderCommand(
    val requestId: UUID,
    val orderId: String,
)
