package io.namastack.demo

fun interface CreateOrderUseCase {
    fun create(command: OrderCommand): Order
}
