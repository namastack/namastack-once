# Namastack Once Reference

Namastack Once provides transactional idempotency for Java and Kotlin applications using Spring Boot.
It identifies an operation by a stable scope and key, commits a marker together with business writes,
and skips work when a marker already exists. It does not persist or replay business results.

This is the initial `0.1.0-SNAPSHOT` development baseline. The project targets Java 17 or later and
Spring Boot 4.1. See the [project overview](../README.md) and [examples](../namastack-once-examples/README.md).

## Dependency

Add the BOM and the starter matching your business database:

```kotlin
implementation(platform("io.namastack:namastack-once-bom:0.1.0-SNAPSHOT"))
implementation("io.namastack:namastack-once-starter-jdbc")
```

For MongoDB, use `namastack-once-starter-mongodb` instead. JDBC applications supply a driver and
DataSource. MongoDB applications need a transaction-capable replica set and a MongoDB connection.
Development coordinates require a local source build or the Central snapshot repository; they are
not stable release coordinates.

Before processing traffic, apply the database schema described below. Once does not run migrations.
The examples demonstrate application-owned initialization.

## Modules

| Module | Purpose |
|--------|---------|
| `namastack-once-api` | Execution contracts, annotations, requests/results and retention policies |
| `namastack-once-core` | Identity normalization, transactions, annotation interception and cleanup scheduling |
| `namastack-once-jdbc` | JDBC marker persistence, database-specific SQL and cleanup |
| `namastack-once-mongodb` | Native MongoDB marker persistence and transactional cleanup |
| `namastack-once-starter-jdbc` | JDBC starter |
| `namastack-once-starter-mongodb` | MongoDB starter |
| `namastack-once-observability-api` | Observation contexts and conventions |
| `namastack-once-observability` | Optional Micrometer metrics and tracing |
| `namastack-once-bom` | Published module version alignment |
| `namastack-once-integration-tests` | Shared database and transaction contracts; not published |

## Identity

A scope is an explicit, stable namespace for application keys. The same key in different scopes
identifies different operations. Instances sharing a physical marker table or collection share the
same identity namespace. Include a tenant or source-system identifier when keys are not globally unique.

Default key conversion supports strings, UUIDs, numbers and enums. Application Spring `Converter`
beans can add stable key types. Keys are not trimmed or case-folded. Changing scope names or key
conversion changes the operation's identity.

## Programmatic API

Inject `IdempotencyOperations` and invoke it outside an existing transaction:

```java
IdempotencyResult<Order> result = once.execute(
    IdempotencyRequest.of("order.create", command.requestId()),
    () -> IdempotencyResult.executed(useCase.create(command))
);

OrderReply reply = result.fold(
    order -> OrderReply.created(order.id()),
    duplicate -> OrderReply.alreadyProcessed()
);
```

The callback returns `IdempotencyResult.Executed<T>`. Once returns `Duplicate<T>` without calling
it if the identity is already protected. For an action with no business value, return
`IdempotencyResult.executed()`. An executed result is returned to the caller only after commit.

## Annotation API

```java
@Idempotent(scope = "order.create", key = "#command.requestId()")
public IdempotencyResult<Order> handle(OrderCommand command) {
    return IdempotencyResult.executed(useCase.create(command));
}
```

Import annotations from `io.namastack.once.annotation`. Alternatively, annotate one parameter with
`@IdempotencyKey`. Methods return `void`/Kotlin `Unit` or `IdempotencyResult<T>`; duplicates on void
methods return normally without running the method body.

Calls must cross a Spring proxy. Kotlin beans must be open, normally through the Kotlin Spring
plugin. Named SpEL arguments require Java parameter metadata; positional expressions such as `#p0`
also work. Self-invocation and reactive return types are unsupported.

Spring async handlers must return void/Unit. A Spring Modulith `@ApplicationModuleListener` can
share the annotated method when its propagation is `NOT_SUPPORTED`, allowing Once to own the
transaction. See the [Modulith example](../namastack-once-examples/namastack-once-example-modulith).

## Transaction and failure contract

Once owns a new local transaction. Marker writes and every protected business write must use the
same database resource and matching transaction manager. Calls inside existing transactions,
including nested Once calls, are rejected.

An action failure rolls back and propagates. A duplicate rolls back its reservation transaction
and skips the action. Commit failures propagate; the commit outcome can be uncertain. Once does
not retry application work automatically. The caller owns transport retries and acknowledgment
following transaction completion. Remote calls and other external effects are outside this local
transaction and are not protected by its rollback.

## JDBC persistence

Install the matching schema using application migrations:

- [PostgreSQL](../namastack-once-jdbc/src/main/resources/schema/postgresql.sql)
- [MySQL](../namastack-once-jdbc/src/main/resources/schema/mysql.sql)
- [MariaDB](../namastack-once-jdbc/src/main/resources/schema/mariadb.sql)
- [H2](../namastack-once-jdbc/src/main/resources/schema/h2.sql)
- [Oracle](../namastack-once-jdbc/src/main/resources/schema/oracle.sql)
- [SQL Server](../namastack-once-jdbc/src/main/resources/schema/sqlserver.sql)

Preserve the supplied columns and constraints, including the immediate identity primary key and
expiry index. MySQL/MariaDB marker and business tables require InnoDB. The default table is
`once_marker`; configure `namastack.once.jdbc.table-name` and optional `schema-name` for another
location. Names are trusted application configuration. Once does not inspect or migrate schemas.

JDBC business writes can participate through JDBC or JPA when they share the selected DataSource
and matching transaction manager. See the [multiple-instance example](../namastack-once-examples/namastack-once-example-multiple-instances)
for named Once beans. Select a bean programmatically with `@Qualifier` or through an annotation's
`operationsBean` attribute.

## MongoDB persistence

Configure `spring.mongodb.uri` for a replica set and apply the
[collection and index script](../namastack-once-mongodb/src/main/resources/schema/mongodb.js).
Marker operations and business writes must share the MongoDB transaction manager's database factory.
Standalone servers are unsupported; keep the marker collection unsharded.

The default collection is `once_marker`, configurable through
`namastack.once.mongodb.collection-name`. Timestamp fields use Int64 epoch microseconds; preserve
the supplied representation and normal expiry index rather than adding a TTL index.

Contention may raise transient transaction errors rather than return a confirmed duplicate. Once
propagates those failures without automatically replaying the action. See the
[MongoDB example](../namastack-once-examples/namastack-once-example-mongodb).

## Retention and cleanup

Markers are retained indefinitely by default. A duration policy makes a marker eligible for cleanup:

```java
var request = IdempotencyRequest.of("order.process", eventId)
    .withRetentionPolicy(new DurationIdempotencyRetentionPolicy(Duration.ofDays(2)));
```

Retention policies live in `io.namastack.once.retention`. Annotation users can select a policy bean
with `retentionPolicyBean`. Retention starts at reservation. An existing marker still protects its
identity after its expiry timestamp until cleanup actually deletes it. Duplicate calls do not
refresh retention; after deletion, the identity can execute again.

Cleanup drains bounded batches in independent transactions. Configure its defaults as follows:

```yaml
namastack:
  once:
    cleanup:
      enabled: true
      interval: 1h
      batch-size: 1000
      shutdown-timeout: 30s
```

The interval is the delay after a complete pass, and the batch size limits each transaction.
Participating nodes must use synchronized clocks. The scheduler uses Spring's platform or virtual
thread setting; a `TaskScheduler` bean named `onceCleanupScheduler` customizes scheduling.

## Observability

Add `io.namastack:namastack-once-observability` alongside either starter. With an `ObservationRegistry`,
it records `once.execute` and `once.cleanup.batch` observations. Execution outcomes are `executed`,
`duplicate` and `error`; cleanup outcomes are `success` and `error`. Duplicates are normal skips.

Metrics need a meter observation handler; tracing needs a bridge and exporter. Core has no
Micrometer dependency. Customize tags through `OnceExecuteObservationConvention` and
`OnceCleanupObservationConvention`. Application keys and hashes are not exported by the default adapter.
See the [combined Outbox tracing example](../namastack-once-examples/namastack-once-example-outbox-tracing).

## Build and Test

```bash
CI=true ./gradlew build dokkaGeneratePublicationJavadoc
./gradlew test
./gradlew ktlintCheck
./gradlew :namastack-once-integration-tests:test -PdatabaseTests=true
node --test scripts/*.test.mjs
```

Default tests cover unit, H2, JPA, annotations and multiple-instance contracts without Docker.
`-PdatabaseTests=true` enables container-backed database tests and requires Docker. CI runs that suite
on Java 17, 21 and 25, plus separate example smoke tests. Local `build` normally also publishes to
Maven local; `CI=true` skips that finalizer. Maven Central publishing requires signing and publishing
credentials, configured through GitHub Actions secrets in CI.

See [Contributing](../CONTRIBUTING.md), [Releasing](../RELEASING.md),
[Security](../SECURITY.md) and [Governance](../GOVERNANCE.md) for project policies.
