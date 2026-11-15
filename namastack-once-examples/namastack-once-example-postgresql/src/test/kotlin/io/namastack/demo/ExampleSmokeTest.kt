package io.namastack.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@SpringBootTest
@Testcontainers
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

    companion object {
        @Container
        @JvmStatic
        val database = PostgreSQLContainer("postgres:18-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", database::getJdbcUrl)
            registry.add("spring.datasource.username", database::getUsername)
            registry.add("spring.datasource.password", database::getPassword)
        }
    }
}
