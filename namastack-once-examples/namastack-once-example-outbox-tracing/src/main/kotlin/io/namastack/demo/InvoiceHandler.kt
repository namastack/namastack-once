package io.namastack.demo

import io.namastack.once.annotation.IdempotencyKey
import io.namastack.once.annotation.Idempotent
import io.namastack.outbox.routing.OutboxHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class InvoiceHandler(
    private val jdbc: JdbcTemplate,
) {
    @Idempotent(scope = "billing.issue-invoice")
    @KafkaListener(topics = [KafkaConfiguration.ORDERS_TOPIC], groupId = "billing")
    fun issue(
        eventId: String,
        @Header(OutboxHeaders.IDEMPOTENCY_KEY) @IdempotencyKey idempotencyKey: String,
    ) {
        jdbc.update("INSERT INTO demo_invoice (event_id) VALUES (?)", eventId)
    }
}
