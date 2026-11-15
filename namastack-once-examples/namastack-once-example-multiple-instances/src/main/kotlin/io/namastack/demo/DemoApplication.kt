package io.namastack.demo

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.UUID

@SpringBootApplication
class DemoApplication(
    private val handlers: EventHandlers,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    override fun run(vararg args: String) {
        val eventId = UUID.randomUUID().toString()
        repeat(2) {
            handlers.order(eventId)
            handlers.billing(eventId)
        }
        logger.info("One order effect and one billing effect committed for {}", eventId)
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
