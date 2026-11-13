package io.namastack.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Combines durable Spring Modulith event publication with Once duplicate protection.
 *
 * Spring's async advisor dispatches first; Once owns the listener's transaction on the worker thread.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@SpringBootApplication
@EnableAsync
class DemoApplication

/**
 * Publishes the same event twice to demonstrate one invoice across two durable deliveries.
 *
 * The application initializes its sample tables explicitly. The async executor keeps the demo
 * running so the listener can finish; stop the application after inspecting the invoice message.
 *
 * @param args Command-line arguments forwarded to Spring Boot.
 */
fun main(args: Array<String>) {
    val applicationContext = runApplication<DemoApplication>(*args)
    val orders = applicationContext.getBean(OrderService::class.java)
    val transactions = applicationContext.getBean(PlatformTransactionManager::class.java)

    val event = orders.placeOrder(1200)
    TransactionTemplate(transactions).executeWithoutResult { applicationContext.publishEvent(event) }
    println("Published event ${event.eventId} twice; the listener issues one invoice.")
}
