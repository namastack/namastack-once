package io.namastack.demo;

import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone annotated example with application-owned SQL initialization.
 */
@SpringBootApplication
public class DemoApplication {
  /**
   * Invokes one command twice to demonstrate fresh execution and duplicate detection.
   *
   * @param args command-line arguments forwarded to Spring Boot
   */
  public static void main(String[] args) {
    try (var applicationContext = SpringApplication.run(DemoApplication.class, args)) {
      var orderHandler = applicationContext.getBean(IdempotentOrderHandler.class);
      var command = new OrderCommand(UUID.randomUUID(), "order-" + UUID.randomUUID());

      var firstResult = orderHandler.handle(command);
      var duplicateResult = orderHandler.handle(command);

      System.out.println("First call executed: " + firstResult.wasExecuted());
      System.out.println("Second call executed: " + duplicateResult.wasExecuted());
    }
  }
}
