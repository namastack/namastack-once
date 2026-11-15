package io.namastack.demo;

public interface CreateOrderUseCase {
  Order create(OrderCommand command);
}
