package io.namastack.demo

import io.namastack.once.IdempotencyOperations
import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import org.springframework.stereotype.Component

@Component
class ProgrammaticOrderHandler(
    private val idempotencyOperations: IdempotencyOperations,
    private val createOrderUseCase: CreateOrderUseCase,
) {
    fun handle(command: OrderCommand): IdempotencyResult<Order> =
        idempotencyOperations.execute(IdempotencyRequest.of("order.create", command.requestId)) {
            createOrderUseCase.create(command)
        }
}
