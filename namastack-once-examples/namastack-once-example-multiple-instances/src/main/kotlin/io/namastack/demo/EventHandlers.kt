package io.namastack.demo

import io.namastack.once.annotation.Idempotent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Demonstrates the same scope and event identity in two independent marker tables.
 *
 * @param jdbc Accesses business rows through the selected Once transaction.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Component
class EventHandlers(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Handles an order event through the Primary Once instance.
     *
     * @param eventId Stable identity reused for redelivery.
     */
    @Idempotent(scope = "event.process", key = "#eventId")
    fun order(eventId: String) {
        jdbc.update("INSERT INTO demo_effect (category, event_id) VALUES (?, ?)", "order", eventId)
    }

    /**
     * Handles a billing event through its named Once instance.
     *
     * @param eventId Stable identity reused for redelivery.
     */
    @Idempotent(scope = "event.process", key = "#eventId", operationsBean = "billingOnce")
    fun billing(eventId: String) {
        jdbc.update("INSERT INTO demo_effect (category, event_id) VALUES (?, ?)", "billing", eventId)
    }
}
