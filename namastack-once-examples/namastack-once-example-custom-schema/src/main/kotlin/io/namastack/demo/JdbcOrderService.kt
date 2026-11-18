package io.namastack.demo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class JdbcOrderService(
    private val jdbcTemplate: JdbcTemplate,
) : CreateOrderUseCase {
    override fun create(command: OrderCommand): Order {
        jdbcTemplate.update("INSERT INTO demo_order (id) VALUES (?)", command.orderId)

        return Order(command.orderId)
    }
}
