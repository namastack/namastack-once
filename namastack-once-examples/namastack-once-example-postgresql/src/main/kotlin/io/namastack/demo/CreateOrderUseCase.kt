package io.namastack.demo

/**
 * Application input port for creating an order.
 *
 * The port contains no Spring or Once types; idempotency is applied by the inbound adapter.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
fun interface CreateOrderUseCase {
    /**
     * Creates the order described by an application command.
     *
     * @param command Request identity and order data supplied by the adapter.
     * @return The newly created business value.
     */
    fun create(command: OrderCommand): Order
}
