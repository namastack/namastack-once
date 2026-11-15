package io.namastack.demo

import io.namastack.once.annotation.IdempotencyKey
import io.namastack.once.annotation.Idempotent
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class IdempotentOrderHandler(
    private val createOrderUseCase: CreateOrderUseCase,
) {
    @Idempotent(scope = "order.consume")
    fun consume(
        @IdempotencyKey eventId: UUID,
        command: OrderCommand,
    ) {
        createOrderUseCase.create(command)
    }
}
