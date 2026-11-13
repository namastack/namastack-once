package io.namastack.demo

import io.namastack.once.IdempotencyOperations
import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.UUID

/**
 * Configures the standalone programmatic idempotency example.
 *
 * The application explicitly initializes its sample tables through Spring SQL initialization.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@SpringBootApplication
class DemoApplication

/**
 * Runs the same request twice to demonstrate fresh execution followed by duplicate detection.
 *
 * @param args Command-line arguments forwarded to Spring Boot.
 */
fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args).use { applicationContext ->
        val idempotencyOperations = applicationContext.getBean(IdempotencyOperations::class.java)
        val createOrderUseCase = applicationContext.getBean(CreateOrderUseCase::class.java)
        val command = OrderCommand(UUID.randomUUID(), "order-${UUID.randomUUID()}")
        val request = IdempotencyRequest.of("order.create", command.requestId)

        val firstResult =
            idempotencyOperations.execute(request) {
                IdempotencyResult.executed(createOrderUseCase.create(command))
            }
        val duplicateResult =
            idempotencyOperations.execute(request) {
                IdempotencyResult.executed(createOrderUseCase.create(command))
            }

        println("First call executed: ${firstResult.wasExecuted()}")
        println("Second call executed: ${duplicateResult.wasExecuted()}")
    }
}
