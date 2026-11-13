package io.namastack.demo

import java.util.UUID

/**
 * Application command retaining its request identity across delivery retries.
 *
 * @property requestId Stable identity reused when the same command is delivered again.
 * @property orderId Identifier to persist for a newly created order.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@JvmRecord
data class OrderCommand(
    val requestId: UUID,
    val orderId: String,
)
