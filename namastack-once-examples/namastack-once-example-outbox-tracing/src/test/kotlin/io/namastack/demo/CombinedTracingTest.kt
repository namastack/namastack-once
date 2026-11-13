package io.namastack.demo

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.Outbox
import io.namastack.outbox.routing.OutboxHeaders
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.tracing.export.otlp.enabled=false",
        "management.logging.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false",
    ],
)
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@Import(CombinedTracingTest.TracingConfiguration::class)
class CombinedTracingTest {
    companion object {
        @Container
        @JvmStatic
        val kafka = KafkaContainer("apache/kafka:4.2.0")

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(properties: DynamicPropertyRegistry) {
            properties.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var outbox: Outbox

    @Autowired private lateinit var manager: PlatformTransactionManager

    @Autowired private lateinit var registry: ObservationRegistry

    @Autowired private lateinit var exporter: InMemorySpanExporter

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Test
    fun `trace crosses Outbox and Kafka while the consumer deduplicates repeated publication`(output: CapturedOutput) {
        val id = UUID.randomUUID().toString()
        val client = RestClient.create("http://localhost:$port")
        val firstResponse =
            client
                .get()
                .uri("/demo/orders/$id")
                .retrieve()
                .body(Map::class.java)!!
        val duplicateResponse =
            client
                .get()
                .uri("/demo/orders/$id")
                .retrieve()
                .body(Map::class.java)!!
        assertThat(firstResponse["status"]).isEqualTo("executed")
        assertThat(duplicateResponse["status"]).isEqualTo("duplicate")
        val traceId = firstResponse["traceId"] as String
        val traceparent = firstResponse["traceparent"] as String
        assertThat(traceId).matches("[0-9a-f]{32}")
        assertThat(traceparent).matches("00-$traceId-[0-9a-f]{16}-[0-9a-f]{2}")
        assertThat(traceparent.takeLast(2).toInt(16) and 1).isEqualTo(1)
        assertThat(output.out).contains("requestId=$id, traceId=$traceId, traceparent=$traceparent")
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertThat(exporter.finishedSpanItems.count { it.name == "outbox record attempt" }).isEqualTo(1)
            assertThat(
                exporter.finishedSpanItems.count {
                    it.kind == SpanKind.CONSUMER &&
                        it.attributes.get(AttributeKey.stringKey("messaging.system")) == "kafka"
                },
            ).isEqualTo(1)
            assertThat(
                exporter.finishedSpanItems.count {
                    it.attributes.get(AttributeKey.stringKey("once.scope")) ==
                        "billing.issue-invoice"
                },
            ).isEqualTo(1)
        }
        val firstSpans = exporter.finishedSpanItems
        val root = firstSpans.single { it.kind == SpanKind.SERVER && it.traceId == traceId }
        val schedule = firstSpans.single { it.name == "outbox schedule" }
        val producer =
            firstSpans.single {
                it.name == "once execute" &&
                    it.attributes.get(AttributeKey.stringKey("once.outcome")) == "executed" &&
                    it.attributes.get(AttributeKey.stringKey("once.scope")) == "orders.create"
            }
        val attempt = firstSpans.single { it.name == "outbox record attempt" }
        val handler = firstSpans.single { it.name == "outbox process" }
        val consumer =
            firstSpans.single {
                it.attributes.get(AttributeKey.stringKey("once.scope")) ==
                    "billing.issue-invoice"
            }
        assertThat(producer.parentSpanId).isEqualTo(root.spanId)
        assertThat(schedule.parentSpanId).isEqualTo(producer.spanId)
        assertThat(attempt.parentSpanId).isEqualTo(schedule.spanId)
        assertThat(handler.parentSpanId).isEqualTo(attempt.spanId)
        val kafkaSend =
            firstSpans.single {
                it.kind == SpanKind.PRODUCER &&
                    it.attributes.get(AttributeKey.stringKey("messaging.system")) == "kafka"
            }
        val kafkaReceive =
            firstSpans.single {
                it.kind == SpanKind.CONSUMER &&
                    it.attributes.get(AttributeKey.stringKey("messaging.system")) == "kafka"
            }
        assertThat(kafkaSend.parentSpanId).isEqualTo(handler.spanId)
        assertThat(kafkaReceive.parentSpanId).isEqualTo(kafkaSend.spanId)
        assertThat(consumer.parentSpanId).isEqualTo(kafkaReceive.spanId)
        assertThat(listOf(producer, schedule, attempt, handler, kafkaSend, kafkaReceive, consumer).map { it.traceId })
            .containsOnly(root.traceId)

        // A second publication with the same event identity simulates redelivery to billing.
        Observation.createNotStarted("test.redelivery", registry).observe {
            TransactionTemplate(manager).executeWithoutResult { outbox.schedule(OrderPlaced(id), id) }
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            val spans = exporter.finishedSpanItems
            assertThat(spans.count { it.name == "outbox record attempt" }).isEqualTo(2)
            assertThat(
                spans.count {
                    it.kind == SpanKind.PRODUCER &&
                        it.attributes.get(AttributeKey.stringKey("messaging.system")) == "kafka"
                },
            ).isEqualTo(2)
            assertThat(
                spans.count {
                    it.kind == SpanKind.CONSUMER &&
                        it.attributes.get(AttributeKey.stringKey("messaging.system")) == "kafka"
                },
            ).isEqualTo(2)
            assertThat(
                spans
                    .filter { it.attributes.get(AttributeKey.stringKey("once.scope")) == "billing.issue-invoice" }
                    .map { it.attributes.get(AttributeKey.stringKey("once.outcome")) },
            ).containsExactly("executed", "duplicate")
        }
        // The header, not the payload or partition key, determines the Once identity.
        val changedPayload = "$id-changed-payload"
        val record = ProducerRecord<String, String>(KafkaConfiguration.ORDERS_TOPIC, changedPayload, changedPayload)
        record.headers().add(RecordHeader(OutboxHeaders.IDEMPOTENCY_KEY, id.toByteArray(Charsets.UTF_8)))
        kafkaTemplate.send(record).get(10, SECONDS)
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            assertThat(
                exporter.finishedSpanItems
                    .filter { it.attributes.get(AttributeKey.stringKey("once.scope")) == "billing.issue-invoice" }
                    .map { it.attributes.get(AttributeKey.stringKey("once.outcome")) },
            ).containsExactly("executed", "duplicate", "duplicate")
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_order", Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM demo_invoice", Int::class.java)).isEqualTo(1)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TracingConfiguration {
        @Bean fun spanExporter(): InMemorySpanExporter = InMemorySpanExporter.create()

        @Bean(destroyMethod = "close")
        fun openTelemetry(exporter: InMemorySpanExporter): OpenTelemetry =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build(),
                ).build()
    }
}
