package io.namastack.demo

import io.namastack.once.annotation.IdempotencyKey
import io.namastack.once.annotation.Idempotent
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Inbound adapter accepting first deliveries and confirmed duplicates as normal completion.
 *
 * @param createOrderUseCase Application input port without Spring or Once annotations.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Component
class IdempotentOrderHandler(
    private val createOrderUseCase: CreateOrderUseCase,
) {
    /**
     * Creates an order only when this event identity has not already been handled.
     *
     * A duplicate returns normally without calling the use case. An application failure propagates
     * after rollback so the surrounding consumer can apply its retry policy.
     *
     * @param eventId Stable event identity reused for delivery retries.
     * @param command Application command whose business changes join the Once transaction.
     */
    @Idempotent(scope = "order.consume")
    fun consume(
        @IdempotencyKey eventId: UUID,
        command: OrderCommand,
    ) {
        createOrderUseCase.create(command)
    }
}
