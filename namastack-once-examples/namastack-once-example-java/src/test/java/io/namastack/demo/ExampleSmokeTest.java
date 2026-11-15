package io.namastack.demo;

import io.namastack.once.IdempotencyOperations;
import io.namastack.once.IdempotencyRequest;
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
  @Autowired private IdempotencyOperations once;
  @Autowired private CreateOrderUseCase useCase;

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

  @Test
  void programmaticCallbacksReturnBusinessValuesDirectly() {
    var command = new OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString());
    var request = IdempotencyRequest.of("order.programmatic", command.requestId());
    var first = once.execute(request, () -> useCase.create(command));
    var duplicate = once.execute(request, () -> useCase.create(command));

    assertThat(first.fold(Order::id, context -> "duplicate")).isEqualTo(command.orderId());
    assertThat(duplicate.wasExecuted()).isFalse();
  }

  @Test
  void runnableActionsNeedNoReturnValueAndAreSkippedOnDuplicates() {
    var command = new OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString());
    var request = IdempotencyRequest.of("order.no-value", command.requestId());
    var first = once.executeWithoutResult(request, () -> useCase.create(command));
    var duplicate = once.executeWithoutResult(request, () -> useCase.create(command));

    assertThat(first.wasExecuted()).isTrue();
    assertThat(duplicate.wasExecuted()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_order WHERE id = ?", Integer.class, command.orderId())).isEqualTo(1);
  }
}
