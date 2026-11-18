package io.namastack.demo

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.UUID

@SpringBootApplication
class DemoApplication(
    private val orders: ProgrammaticOrderHandler,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    override fun run(vararg args: String) {
        val command = OrderCommand(UUID.randomUUID(), "order-${UUID.randomUUID()}")
        val firstResult = orders.handle(command)
        val duplicateResult = orders.handle(command)

        logger.info("First call executed: {}", firstResult.wasExecuted())
        logger.info("Second call executed: {}", duplicateResult.wasExecuted())
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
