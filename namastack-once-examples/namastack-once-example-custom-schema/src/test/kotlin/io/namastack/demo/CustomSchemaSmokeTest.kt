package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.util.UUID

@SpringBootTest
class CustomSchemaSmokeTest {
    @Autowired private lateinit var orders: ProgrammaticOrderHandler

    @Autowired private lateinit var handler: IdempotentOrderHandler

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `programmatic execution stores one marker in the custom schema and table`() {
        val command = OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString())
        val markersBefore = markerCount()

        val first = orders.handle(command)
        val duplicate = orders.handle(command)

        assertThat(first.fold({ it.id }, { "duplicate" })).isEqualTo(command.orderId)
        assertThat(duplicate.wasExecuted()).isFalse()
        assertThat(orderCount(command.orderId)).isEqualTo(1)
        assertThat(markerCount()).isEqualTo(markersBefore + 1)
    }

    @Test
    fun `annotated execution uses the same custom marker table`() {
        val command = OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString())
        val markersBefore = markerCount()

        handler.consume(command.requestId, command)
        handler.consume(command.requestId, command)

        assertThat(orderCount(command.orderId)).isEqualTo(1)
        assertThat(markerCount()).isEqualTo(markersBefore + 1)
    }

    @Test
    fun `default marker table is not created`() {
        val defaultTables = jdbc.queryForObject<Int>(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ONCE_MARKER'",
        )

        assertThat(defaultTables).isZero()
    }

    private fun markerCount(): Int =
        checkNotNull(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM idempotency.processed_event"))

    private fun orderCount(orderId: String): Int =
        checkNotNull(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM demo_order WHERE id = ?", orderId))
}
