package io.namastack.demo

import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Commits an order and its durable Modulith event publication together.
 *
 * @param jdbc Persists the order through the producer transaction.
 * @param events Publishes the event to Spring Modulith's publication registry.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Service
class OrderService(
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Places an order and schedules asynchronous invoice creation after commit.
     *
     * @param amountCents Amount carried by the integration event.
     * @return Published event, including its stable identity for inspection and redelivery tests.
     */
    @Transactional
    fun placeOrder(amountCents: Long): OrderPlaced {
        val event = OrderPlaced(UUID.randomUUID(), UUID.randomUUID(), amountCents)
        jdbc.update("INSERT INTO demo_order (id, amount_cents) VALUES (?, ?)", event.orderId.toString(), amountCents)
        events.publishEvent(event)
        return event
    }
}
