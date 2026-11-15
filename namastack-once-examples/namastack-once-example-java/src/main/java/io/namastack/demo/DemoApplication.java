package io.namastack.demo;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication implements CommandLineRunner {
  private static final Logger logger = LoggerFactory.getLogger(DemoApplication.class);
  private final IdempotentOrderHandler orders;

  public DemoApplication(IdempotentOrderHandler orders) {
    this.orders = orders;
  }

  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }

  @Override
  public void run(String... args) {
    var command = new OrderCommand(UUID.randomUUID(), "order-" + UUID.randomUUID());
    var firstResult = orders.handle(command);
    var duplicateResult = orders.handle(command);

    logger.info("First call executed: {}", firstResult.wasExecuted());
    logger.info("Second call executed: {}", duplicateResult.wasExecuted());
  }
}
