package io.namastack.demo

import io.namastack.outbox.kafka.KafkaOutboxRouting
import io.namastack.outbox.kafka.kafkaOutboxRouting
import io.namastack.outbox.routing.OutboxHeaders
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/** Provisions the single-partition topic used by the producer and consumer in this demo. */
@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {
    companion object {
        /** Topic transporting stable order event identities. */
        const val ORDERS_TOPIC: String = "order-placed"
    }

    /** Creates the local demo topic with one partition and one replica. @return Topic definition. */
    @Bean
    fun ordersTopic(): NewTopic =
        TopicBuilder
            .name(ORDERS_TOPIC)
            .partitions(1)
            .replicas(1)
            .build()

    /**
     * Routes order events through the built-in Kafka handler with a stable business idempotency key.
     * An explicit header overrides the default Outbox record ID, retaining identity across republication.
     * @return Routing for order events, mapping the Kafka value to the plain event identity.
     */
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
