package io.namastack.demo

/**
 * Business value produced by a fresh order creation.
 *
 * Once returns this value from the current invocation without storing it for duplicate replay.
 *
 * @property id Identifier persisted by the order service.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@JvmRecord
data class Order(
    val id: String,
)
