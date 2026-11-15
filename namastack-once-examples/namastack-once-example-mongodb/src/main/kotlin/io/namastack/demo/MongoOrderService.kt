package io.namastack.demo

import org.bson.Document
import org.springframework.context.annotation.DependsOn
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@DependsOn("exampleMongoSchema")
class MongoOrderService(
    private val mongoTemplate: MongoTemplate,
) : CreateOrderUseCase {
    @Transactional
    override fun create(command: OrderCommand): Order {
        mongoTemplate.insert(Document("_id", command.orderId), "demo_order")
        return Order(command.orderId)
    }
}
