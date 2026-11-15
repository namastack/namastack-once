package io.namastack.demo

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
class DemoApplication(
    private val orders: OrderService,
) : CommandLineRunner {
    private val logger = LoggerFactory.getLogger(DemoApplication::class.java)

    override fun run(vararg args: String) {
        val event = orders.placeOrder(1200)
        orders.republish(event)
        logger.info("Published event {} twice; the listener issues one invoice.", event.eventId)
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
