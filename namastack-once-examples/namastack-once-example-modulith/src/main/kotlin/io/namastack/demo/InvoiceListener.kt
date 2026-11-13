package io.namastack.demo

import io.namastack.once.annotation.Idempotent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import java.util.UUID

/**
 * Creates invoices asynchronously inside the transaction owned by Once.
 *
 * @param jdbc Writes invoice data on the same connection as the Once marker.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Component
class InvoiceListener(
    private val jdbc: JdbcTemplate,
) {
    /**
     * Issues one invoice per stable event identity, including after publication retries.
     *
     * The amount check follows the INSERT deliberately: a rejected amount demonstrates that
     * the Once-owned transaction rolls back the invoice and marker together.
     *
     * @param event Order event delivered by Spring Modulith.
     * @throws IllegalArgumentException if the amount is invalid, rolling back both invoice and marker.
     */
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
        println("Issued invoice for event ${event.eventId}")
    }
}
