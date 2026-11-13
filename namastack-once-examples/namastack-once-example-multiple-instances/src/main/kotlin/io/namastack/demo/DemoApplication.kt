package io.namastack.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import java.util.UUID

/**
 * Configures two fixed Once instances sharing a datasource with separate marker tables.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@SpringBootApplication
class DemoApplication

/**
 * Delivers the same event twice to each handler and persists one effect per marker table.
 *
 * @param args Command-line arguments forwarded to Spring Boot.
 */
fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args).use { context ->
        val handlers = context.getBean(EventHandlers::class.java)
        val eventId = UUID.randomUUID().toString()
        repeat(2) {
            handlers.order(eventId)
            handlers.billing(eventId)
        }
        println("One order effect and one billing effect committed for $eventId")
    }
}
