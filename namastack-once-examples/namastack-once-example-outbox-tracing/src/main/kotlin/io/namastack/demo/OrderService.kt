package io.namastack.demo

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.namastack.once.IdempotencyResult
import io.namastack.once.annotation.Idempotent
import io.namastack.outbox.Outbox
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** Stable event identity survives transport redelivery. @property eventId Original request identity. */
data class OrderPlaced(
    val eventId: String,
)

/** Atomically reserves the request, writes the order, and schedules its event. */
@Service
class OrderService(
    private val jdbc: JdbcTemplate,
    private val outbox: Outbox,
) {
    /**
     * Places an order once, with all three writes on the shared JDBC transaction.
     * @param requestId Stable caller-provided request identity.
     * @return Newly created identity or a duplicate outcome after transaction completion.
     */
    @Idempotent(scope = "orders.create", key = "#p0")
    fun place(requestId: String): IdempotencyResult<String> {
        jdbc.update("INSERT INTO demo_order (id) VALUES (?)", requestId)
        outbox.schedule(OrderPlaced(requestId), requestId)
        return IdempotencyResult.executed(requestId)
    }
}

/** Exposes a repeatable request for inspecting execution and duplicate spans. */
@RestController
class OrderController(
    private val orders: OrderService,
    private val tracer: Tracer,
    private val propagator: Propagator,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Runs a browser-friendly demo request and exposes its HTTP trace for inspection.
     * This GET deliberately creates sample data; it is a demo endpoint, not a production API design.
     * @param requestId Stable identity; reloading the same URL demonstrates a duplicate.
     * @return Execution status, identity, and the active request's trace ID and propagation header.
     */
    @GetMapping("/demo/orders/{requestId}")
    fun create(
        @PathVariable requestId: String,
    ): Map<String, String> {
        val result = orders.place(requestId)
        val headers = mutableMapOf<String, String>()
        val context = tracer.currentSpan()?.context()
        if (context != null) {
            propagator.inject(context, headers) { carrier, key, value -> carrier!![key] = value }
        }
        val traceId = context?.traceId().orEmpty()
        val traceparent = headers["traceparent"].orEmpty()
        logger.info(
            "Order request completed: requestId={}, traceId={}, traceparent={}",
            requestId,
            traceId,
            traceparent,
        )
        return mapOf(
            "status" to if (result.wasExecuted()) "executed" else "duplicate",
            "id" to requestId,
            "traceId" to traceId,
            "traceparent" to traceparent,
        )
    }
}
