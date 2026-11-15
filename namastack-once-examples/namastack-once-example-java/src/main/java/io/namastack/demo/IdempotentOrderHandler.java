package io.namastack.demo;

import io.namastack.once.IdempotencyResult;
import io.namastack.once.annotation.Idempotent;
import org.springframework.stereotype.Component;

@Component
public class IdempotentOrderHandler {
  private final CreateOrderUseCase createOrderUseCase;

  public IdempotentOrderHandler(CreateOrderUseCase createOrderUseCase) {
    this.createOrderUseCase = createOrderUseCase;
  }

  @Idempotent(scope = "order.create", key = "#command.requestId()")
  public IdempotencyResult<Order> handle(OrderCommand command) {
    return IdempotencyResult.executed(createOrderUseCase.create(command));
  }
}
