package io.namastack.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Business implementation participating in the idempotent adapter's local transaction.
 */
@Service
public class JdbcOrderService implements CreateOrderUseCase {
  private final JdbcTemplate jdbcTemplate;

  /**
   * Creates the service using the same datasource as the Once marker repository.
   *
   * @param jdbcTemplate JDBC access to the application database
   */
  public JdbcOrderService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Persists the order and returns the current invocation's business value.
   *
   * @param command order data supplied through the application input port
   * @return the order inserted in the current transaction
   */
  @Override
  public Order create(OrderCommand command) {
    jdbcTemplate.update("INSERT INTO demo_order (id) VALUES (?)", command.orderId());

    return new Order(command.orderId());
  }
}
