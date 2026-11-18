# Namastack Once examples

| Example | Purpose | Database |
|---------|---------|----------|
| [Java](namastack-once-example-java) | Result-returning adapter annotation and framework-free input port | H2 |
| [Kotlin](namastack-once-example-kotlin) | Programmatic API and void annotation | H2 |
| [Custom schema and table](namastack-once-example-custom-schema) | Configured schema and marker table with application-managed SQL | H2 |
| [Multiple instances](namastack-once-example-multiple-instances) | Named Once beans and separate marker tables | H2 |
| [Spring Modulith](namastack-once-example-modulith) | Async module listener, atomic marker writes and durable publication retries | H2 |
| [MongoDB](namastack-once-example-mongodb) | Native MongoDB transactions, exact microsecond retention, annotated and programmatic APIs | MongoDB replica set |
| [PostgreSQL](namastack-once-example-postgresql) | Same contracts on a real database | PostgreSQL 18 |

Each example is an independent Gradle project, like Namastack Outbox examples. Shared dependency
versions live in `gradle/libs.versions.toml`. Use `-PonceSource=../..` inside an example to test the
source checkout through Gradle composite substitution, or publish the root build to Maven local.
The command-line examples use constructor injection and `CommandLineRunner`, matching the Outbox
examples. Their `main` methods only start Spring Boot. Programmatic Once calls live in
`ProgrammaticOrderHandler`; annotated calls live in `IdempotentOrderHandler` or event listeners.
The tracing example exposes HTTP endpoints instead.

No broker, messaging integration, or observability module is required for the basic examples.

- [Once + Outbox tracing](namastack-once-example-outbox-tracing): shared traces across browser GET requests, Outbox publication, Kafka, and an idempotent invoice consumer.
