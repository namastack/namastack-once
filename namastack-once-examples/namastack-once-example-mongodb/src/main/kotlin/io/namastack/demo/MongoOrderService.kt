package io.namastack.demo

import org.bson.Document
import org.springframework.context.annotation.DependsOn
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Persists orders in the same MongoDB transaction as the Once marker.
 *
 * @param mongoTemplate Template using the same database factory as Once.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Service
@DependsOn("exampleMongoSchema")
class MongoOrderService(
    private val mongoTemplate: MongoTemplate,
) : CreateOrderUseCase {
    /**
     * Joins the Once-owned transaction and creates one business document.
     *
     * @param command Order identity and request metadata supplied by the application.
     * @return The newly inserted business value.
     */
    @Transactional
    override fun create(command: OrderCommand): Order {
        mongoTemplate.insert(Document("_id", command.orderId), "demo_order")
        return Order(command.orderId)
    }
}
