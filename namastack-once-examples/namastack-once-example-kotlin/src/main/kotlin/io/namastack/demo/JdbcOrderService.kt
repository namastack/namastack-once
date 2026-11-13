package io.namastack.demo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Persists business changes within the active transaction selected by the idempotent adapter.
 *
 * @param jdbcTemplate JDBC access to the same datasource as the Once marker repository.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Service
class JdbcOrderService(
    private val jdbcTemplate: JdbcTemplate,
) : CreateOrderUseCase {
    /**
     * Inserts the order and returns its newly created business value.
     *
     * @param command Order data supplied by the application input port.
     * @return The order inserted in the current transaction.
     */
    override fun create(command: OrderCommand): Order {
        jdbcTemplate.update("INSERT INTO demo_order (id) VALUES (?)", command.orderId)

        return Order(command.orderId)
    }
}
