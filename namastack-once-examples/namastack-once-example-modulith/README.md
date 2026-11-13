# Namastack Once Spring Modulith example

Demonstrates `@ApplicationModuleListener` and `@Idempotent` on the same method with Spring Modulith's
JDBC publication registry. The application publishes one order event twice and issues one invoice.

```kotlin
@ApplicationModuleListener(id = "billing.issue-invoice", propagation = Propagation.NOT_SUPPORTED)
@Idempotent(scope = "billing.issue-invoice", key = "#event.eventId")
fun on(event: OrderPlaced) {
    // Write the invoice using the listener's datasource.
}
```

Spring dispatches the invocation to a worker thread. `NOT_SUPPORTED` suspends any ambient transaction
before Once starts its own, inserts the marker, and invokes the method body. The invoice and marker
commit together before the listener returns. For duplicates, Once rolls back the unused reservation
transaction, skips the body, and returns normally, allowing Modulith to mark the publication completed.
Use a stable event ID across redeliveries. The default listener propagation (`REQUIRES_NEW`) is rejected
because Once does not join caller-owned transactions. Both annotations remain on the same method.

The producer's order and publication record use a separate transaction. Listener failure rolls back
the invoice and marker; Modulith retains the failed publication for resubmission. If a commit
succeeds but its acknowledgement is lost, resubmission finds the committed marker and skips the body.
Remote HTTP calls and broker sends are outside the local database transaction.

Run from this directory against the source checkout:

```bash
./gradlew bootRun -PonceSource=../..
./gradlew test -PonceSource=../..
```

The console reports two publications and one invoice. Stop the application with Ctrl+C.
Omit `-PonceSource` to consume the version in the shared example catalog from Maven local/Central.

The six integration tests exercise real asynchronous listeners and the durable registry: successful
processing with a Once-owned transaction, duplicate publication, producer rollback, listener failure, commit failure with successful
resubmission, and lost commit acknowledgement with duplicate suppression on resubmission.
The commit faults are injected by a transaction manager declared inside the test class.

The example uses in-memory H2 and needs no Docker. Spring SQL initialization explicitly applies the
application-owned Once, business, and Modulith scripts in `src/main/resources/db/migration`.
The Modulith script matches version 2.1.2. Neither Once nor Modulith schema auto-creation is enabled.
No Modulith dependency is added to the Once library.
