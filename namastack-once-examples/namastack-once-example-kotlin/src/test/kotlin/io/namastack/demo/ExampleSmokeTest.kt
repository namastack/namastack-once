package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.util.UUID

@SpringBootTest
class ExampleSmokeTest {
    @Autowired private lateinit var orders: ProgrammaticOrderHandler

    @Autowired private lateinit var handler: IdempotentOrderHandler

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `programmatic calls return one fresh order and one duplicate`() {
        val command = OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString())
        val first = orders.handle(command)
        val duplicate = orders.handle(command)

        assertThat(first.fold({ it.id }, { "duplicate" })).isEqualTo(command.orderId)
        assertThat(duplicate.wasExecuted()).isFalse()
    }

    @Test
    fun `void annotation skips duplicate messages`() {
        val command = OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString())

        handler.consume(command.requestId, command)
        handler.consume(command.requestId, command)

        assertThat(
            jdbc.queryForObject<Int>("SELECT COUNT(*) FROM demo_order WHERE id = ?", command.orderId),
        ).isEqualTo(1)
    }
}
