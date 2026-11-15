package io.namastack.demo

import io.namastack.once.annotation.Idempotent
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import java.util.UUID

@Component
class InvoiceListener(
    private val jdbc: JdbcTemplate,
) {
    private val logger = LoggerFactory.getLogger(InvoiceListener::class.java)

    @ApplicationModuleListener(id = "billing.issue-invoice", propagation = Propagation.NOT_SUPPORTED)
    @Idempotent(scope = "billing.issue-invoice", key = "#event.eventId")
    fun on(event: OrderPlaced) {
        jdbc.update(
            "INSERT INTO demo_invoice (id, event_id, order_id, amount_cents) VALUES (?, ?, ?, ?)",
            UUID.randomUUID().toString(),
            event.eventId.toString(),
            event.orderId.toString(),
            event.amountCents,
        )
        require(event.amountCents > 0) { "Invoice amount must be positive" }
        logger.info("Issued invoice for event {}", event.eventId)
    }
}
