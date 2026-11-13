package io.namastack.demo

import io.namastack.once.IdempotencyOperations
import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

@SpringBootTest
class ExampleSmokeTest {
    @Autowired private lateinit var handlers: EventHandlers

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired
    @Qualifier("ordersOnce")
    private lateinit var ordersOnce: IdempotencyOperations

    @Autowired
    @Qualifier("billingOnce")
    private lateinit var billingOnce: IdempotencyOperations

    @Test
    fun `annotations use Primary and explicit instance names to protect separate tables`() {
        val eventId = UUID.randomUUID().toString()
        repeat(2) {
            handlers.order(eventId)
            handlers.billing(eventId)
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_effect WHERE event_id = ?", Int::class.java, eventId))
            .isEqualTo(2)
    }

    @Test
    fun `programmatic selection uses injected instances with the same immutable request`() {
        val request = IdempotencyRequest.of("programmatic", UUID.randomUUID())
        assertThat(ordersOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
        assertThat(billingOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
        assertThat(ordersOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isFalse()
        assertThat(billingOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isFalse()
    }
}
