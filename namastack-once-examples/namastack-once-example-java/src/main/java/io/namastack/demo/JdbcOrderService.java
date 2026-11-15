package io.namastack.demo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcOrderService implements CreateOrderUseCase {
  private final JdbcTemplate jdbcTemplate;

  public JdbcOrderService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Order create(OrderCommand command) {
    jdbcTemplate.update("INSERT INTO demo_order (id) VALUES (?)", command.orderId());

    return new Order(command.orderId());
  }
}
