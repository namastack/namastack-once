package io.namastack.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** HTTP demo combining Once transactions with durable Outbox delivery and shared tracing. */
@SpringBootApplication
class DemoApplication

/** Starts the demo HTTP server. @param args Spring Boot command-line arguments. */
fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
