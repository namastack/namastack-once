package io.namastack.demo

import io.namastack.once.IdempotencyOperations
import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.util.UUID

@SpringBootTest
@Testcontainers
class ExampleMongoSmokeTest {
    @Autowired private lateinit var once: IdempotencyOperations

    @Autowired private lateinit var useCase: CreateOrderUseCase

    @Autowired private lateinit var handler: IdempotentOrderHandler

    @Autowired private lateinit var mongo: MongoTemplate

    @Test
    fun `programmatic execution creates one order and a duplicate without replaying the result`() {
        val command = OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString())
        val identity = IdempotencyRequest.of("order.create", command.requestId)
        val first = once.execute(identity) { IdempotencyResult.executed(useCase.create(command)) }
        val second = once.execute<Order>(identity) { error("duplicate must skip") }
        assertThat(first.fold({ it.id }, { "duplicate" })).isEqualTo(command.orderId)
        assertThat(second.wasExecuted()).isFalse()
        assertThat(orderCount(command.orderId)).isEqualTo(1)
    }

    @Test
    fun `the annotated adapter skips a redelivered event`() {
        val command = OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString())
        handler.consume(command.requestId, command)
        handler.consume(command.requestId, command)
        assertThat(orderCount(command.orderId)).isEqualTo(1)
    }

    @Test
    fun `a business failure rolls back both order and marker and permits a later execution`() {
        val command = OrderCommand(UUID.randomUUID(), UUID.randomUUID().toString())
        val identity = IdempotencyRequest.of("order.rollback", command.requestId)
        assertThatThrownBy {
            once.execute<Void?>(identity) {
                useCase.create(command)
                error("business failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(orderCount(command.orderId)).isZero()
        val retried = once.execute(identity) { IdempotencyResult.executed(useCase.create(command)) }
        assertThat(retried.wasExecuted()).isTrue()
        assertThat(orderCount(command.orderId)).isEqualTo(1)
    }

    private fun orderCount(orderId: String): Long =
        mongo.count(Query(Criteria.where("_id").`is`(orderId)), "demo_order")

    companion object {
        @Container
        @JvmStatic
        val database = MongoDBContainer("mongo:9.0.4").withReplicaSet()

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.mongodb.uri") { database.getReplicaSetUrl("once_example") }
        }
    }
}
