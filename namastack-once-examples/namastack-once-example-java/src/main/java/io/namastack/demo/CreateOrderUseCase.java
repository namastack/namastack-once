package io.namastack.demo;

/**
 * Application input port for creating orders without Spring or Once dependencies.
 */
public interface CreateOrderUseCase {
  /**
   * Creates the order described by an application command.
   *
   * @param command request identity and order data supplied by the adapter
   * @return the newly created order
   */
  Order create(OrderCommand command);
}
