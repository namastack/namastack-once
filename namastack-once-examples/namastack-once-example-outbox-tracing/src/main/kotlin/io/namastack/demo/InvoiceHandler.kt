package io.namastack.demo

import io.namastack.once.annotation.IdempotencyKey
import io.namastack.once.annotation.Idempotent
import io.namastack.outbox.routing.OutboxHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

/** Kafka consumer that commits its invoice and Once marker in the same local JDBC transaction. */
@Component
class InvoiceHandler(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Processes the stable event identity under Kafka's restored tracing scope.
     * Kafka offset acknowledgement follows successful processing; redelivery skips the database write.
     * @param eventId Business payload identifying the order to invoice.
     * @param idempotencyKey Required Idempotency-Key header, stable across retries and repeated publication.
     */
    @Idempotent(scope = "billing.issue-invoice")
    @KafkaListener(topics = [KafkaConfiguration.ORDERS_TOPIC], groupId = "billing")
    fun issue(
        eventId: String,
        @Header(OutboxHeaders.IDEMPOTENCY_KEY) @IdempotencyKey idempotencyKey: String,
    ) {
        jdbc.update("INSERT INTO demo_invoice (event_id) VALUES (?)", eventId)
    }
}
