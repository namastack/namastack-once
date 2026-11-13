package io.namastack.demo;

import io.namastack.once.IdempotencyResult;
import io.namastack.once.annotation.Idempotent;
import org.springframework.stereotype.Component;

/**
 * Inbound adapter applying idempotency without changing the application input port.
 */
@Component
public class IdempotentOrderHandler {
  private final CreateOrderUseCase createOrderUseCase;

  /**
   * Creates the adapter around the application's framework-independent use case.
   *
   * @param createOrderUseCase application input port invoked for fresh requests
   */
  public IdempotentOrderHandler(CreateOrderUseCase createOrderUseCase) {
    this.createOrderUseCase = createOrderUseCase;
  }

  /**
   * Creates an order only when no marker protects the supplied request identity.
   *
   * @param command application command retaining the same request ID across retries
   * @return an executed outcome carrying the new order, or a duplicate outcome without invoking the use case
   */
  @Idempotent(scope = "order.create", key = "#command.requestId()")
  public IdempotencyResult<Order> handle(OrderCommand command) {
    return IdempotencyResult.executed(createOrderUseCase.create(command));
  }
}
