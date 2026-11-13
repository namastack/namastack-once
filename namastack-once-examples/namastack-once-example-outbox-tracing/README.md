# Once + Outbox + Kafka tracing

This demo runs a Kafka producer and consumer in one Spring Boot app, with a real Kafka
broker between them. The code models the boundary between two services without requiring
two application processes. Spring Modulith is not needed: Outbox owns durable publication,
and `@KafkaListener` receives the message from Kafka.

The flow is:

1. A browser request uses `@Idempotent` to commit an order, its marker, and an Outbox record together.
2. The built-in `KafkaOutboxHandler` publishes through `namastack-outbox-kafka`, including an `Idempotency-Key` header.
3. A `@KafkaListener` with `@Idempotent` commits one invoice and its own marker in a local JDBC transaction.

```yaml
namastack:
  outbox:
    kafka:
      idempotency-header-enabled: true
```

```kotlin
@Bean
fun kafkaOutboxRouting(): KafkaOutboxRouting = kafkaOutboxRouting {
    route(OutboxPayloadSelector.type(OrderPlaced::class.java)) {
        target(ORDERS_TOPIC)
        key { payload, _ -> (payload as OrderPlaced).eventId }
        headers { payload, _ -> mapOf(OutboxHeaders.IDEMPOTENCY_KEY to (payload as OrderPlaced).eventId) }
        mapping { payload, _ -> (payload as OrderPlaced).eventId }
    }
}

@KafkaListener(topics = [KafkaConfiguration.ORDERS_TOPIC], groupId = "billing")
@Idempotent(scope = "billing.issue-invoice")
fun issue(
    eventId: String,
    @Header(OutboxHeaders.IDEMPOTENCY_KEY) @IdempotencyKey idempotencyKey: String,
) {
    jdbc.update("INSERT INTO demo_invoice (event_id) VALUES (?)", eventId)
}
```

Enabling the integration's header feature supplies the persisted Outbox record ID by default,
which stays stable across retries of that record. This example explicitly overrides the header
with the business event ID so a new Outbox record for the same event is also deduplicated.
The Kafka record key controls partitioning; `Idempotency-Key` controls duplicate protection.
They happen to have the same value here, but serve different purposes.

The consumer requires the header and marks that parameter with `@IdempotencyKey`; no SpEL
expression is needed. The integration encodes it as UTF-8 and Spring Kafka converts the header
to the listener's String argument. Missing headers fail message binding before application work;
blank keys are rejected by Once. Producers must reuse a key only for the same logical operation.
The built-in handler replaces the custom publisher, avoiding multiple handlers publishing the
same scheduled event. It waits for the send result before completing its Outbox invocation.

The publisher has no Once marker: it provides at-least-once delivery. If Kafka accepts a
message but Outbox completion fails, publishing can repeat. Kafka producer idempotence
handles producer protocol retries; it does not deduplicate new application-level sends.
The consumer therefore uses the header identity for duplicate protection. Its
marker and invoice commit before Kafka acknowledges the consumed offset. Redelivery
skips the invoice write and lets the listener acknowledge the message normally.

Do not put `@Transactional` on the listener: Once must start and own the database transaction.
In separate services each service would own its database and Once markers. This compact
demo uses one H2 database and distinct producer/consumer scopes. Kafka carries the event ID
as a plain string to keep serialization out of the example.

## Run and open in the browser

This example targets the adjacent Outbox source checkout (`1.11.1-SNAPSHOT`). From this directory:

```sh
docker compose up -d
./gradlew bootRun -PonceSource=../.. -PoutboxSource=../../../namastack-outbox
```

Alternatively publish the matching Once and Outbox snapshots to Maven local and omit the
source properties. Java 17 or later and the configured Gradle toolchains are required.
Kafka is available at localhost:9092; set `KAFKA_BOOTSTRAP_SERVERS` to use another broker.
The example uses in-memory H2; restarting the app clears orders and markers, while the
Docker broker can still contain messages. For a fresh demo, also recreate the broker.
Outbox creates its own tables; the application explicitly initializes Once and demo tables.

Open http://localhost:8080/demo/orders/request-123 in the browser. This GET demo endpoint
creates sample data. The first response is `executed`; reloading returns `duplicate`
and schedules no new event. Use another request ID in the URL for a fresh order.

```json
{
  "status": "executed",
  "id": "request-123",
  "traceId": "...",
  "traceparent": "00-<traceId>-<requestSpanId>-01"
}
```

The application logs the same `traceId` and `traceparent` after the request's order operation
finishes. This identifies the HTTP request trace; asynchronous Kafka processing can still
be running. A duplicate request has its own trace and no publishing or consuming children.

Open Grafana at http://localhost:3000 (admin/admin if prompted), choose Explore and Tempo,
and search for the returned trace ID. The service is `once-outbox-tracing`; every request is sampled.

```text
GET /demo/orders/{requestId}
└─ once execute [scope=orders.create, outcome=executed]
   └─ outbox schedule
      └─ outbox record attempt                 (asynchronous, after commit)
         └─ outbox process
            └─ order-placed send               (Kafka producer)
               └─ order-placed process         (Kafka consumer)
                  └─ once execute [scope=billing.issue-invoice, outcome=executed]
```

Both libraries share Spring Boot's observation registry and OpenTelemetry tracing bridge.
Outbox persists trace headers during scheduling and restores them for each delivery attempt.
Kafka template and listener observations are explicitly enabled, propagating the active
context through Kafka headers to the listener thread. Ended parent spans do not need to
wait for asynchronous children. Outbox retries are siblings under the original scheduling span.

## Test

Docker is required for the real Kafka Testcontainer. Grafana is not required: the test
exports spans synchronously to an in-memory OpenTelemetry exporter.

```sh
./gradlew check -PonceSource=../.. -PoutboxSource=../../../namastack-outbox
```

`CombinedTracingTest` makes actual GET requests, checks the trace ID and `traceparent` in
the response and logs, and asserts every exported parent span through Outbox and Kafka.
It then republishes the same event through Outbox and the real broker, verifying that the
consumer reports `duplicate`. A further Kafka message retains the original header but changes
its body and record key, proving that Once reads the header rather than either of those values.
Only one order and invoice exist.

```sh
docker compose down
```
