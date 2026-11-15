package io.namastack.demo

import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrderService(
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun placeOrder(amountCents: Long): OrderPlaced {
        val event = OrderPlaced(UUID.randomUUID(), UUID.randomUUID(), amountCents)
        jdbc.update("INSERT INTO demo_order (id, amount_cents) VALUES (?, ?)", event.orderId.toString(), amountCents)
        events.publishEvent(event)
        return event
    }

    @Transactional
    fun republish(event: OrderPlaced) {
        events.publishEvent(event)
    }
}
