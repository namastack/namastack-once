package io.namastack.demo

import io.namastack.outbox.kafka.KafkaOutboxRouting
import io.namastack.outbox.kafka.kafkaOutboxRouting
import io.namastack.outbox.routing.OutboxHeaders
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {
    companion object {
        const val ORDERS_TOPIC: String = "order-placed"
    }

    @Bean
    fun ordersTopic(): NewTopic =
        TopicBuilder
            .name(ORDERS_TOPIC)
            .partitions(1)
            .replicas(1)
            .build()

    @Bean
    fun kafkaOutboxRouting(): KafkaOutboxRouting =
        kafkaOutboxRouting {
            route(OutboxPayloadSelector.type(OrderPlaced::class.java)) {
                target(ORDERS_TOPIC)
                key { payload, _ -> (payload as OrderPlaced).eventId }
                headers { payload, _ -> mapOf(OutboxHeaders.IDEMPOTENCY_KEY to (payload as OrderPlaced).eventId) }
                mapping { payload, _ -> (payload as OrderPlaced).eventId }
            }
        }
}
