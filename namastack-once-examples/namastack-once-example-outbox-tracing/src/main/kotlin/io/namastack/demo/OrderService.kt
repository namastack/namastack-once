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

data class OrderPlaced(
    val eventId: String,
)

@Service
class OrderService(
    private val jdbc: JdbcTemplate,
    private val outbox: Outbox,
) {
    @Idempotent(scope = "orders.create", key = "#p0")
    fun place(requestId: String): IdempotencyResult<String> {
        jdbc.update("INSERT INTO demo_order (id) VALUES (?)", requestId)
        outbox.schedule(OrderPlaced(requestId), requestId)
        return IdempotencyResult.executed(requestId)
    }
}

@RestController
class OrderController(
    private val orders: OrderService,
    private val tracer: Tracer,
    private val propagator: Propagator,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

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
