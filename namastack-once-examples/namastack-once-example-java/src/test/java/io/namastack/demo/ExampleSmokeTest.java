package io.namastack.demo;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ExampleSmokeTest {
  @Autowired private IdempotentOrderHandler handler;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void createsOneOrderAndReturnsADuplicateWithoutReplayingTheOrder() {
    var command = new OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString());
    var first = handler.handle(command);
    var second = handler.handle(command);

    assertThat(first.wasExecuted()).isTrue();
    assertThat(first.fold(Order::id, duplicate -> "duplicate")).isEqualTo(command.orderId());
    assertThat(second.wasExecuted()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_order WHERE id = ?", Integer.class, command.orderId())).isEqualTo(1);
  }
}
