package io.namastack.demo

import io.namastack.once.annotation.Idempotent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class EventHandlers(
    private val jdbc: JdbcTemplate,
) {
    @Idempotent(scope = "event.process", key = "#eventId")
    fun order(eventId: String) {
        jdbc.update("INSERT INTO demo_effect (category, event_id) VALUES (?, ?)", "order", eventId)
    }

    @Idempotent(scope = "event.process", key = "#eventId", operationsBean = "billingOnce")
    fun billing(eventId: String) {
        jdbc.update("INSERT INTO demo_effect (category, event_id) VALUES (?, ?)", "billing", eventId)
    }
}
