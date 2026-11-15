# Namastack Once kotlin example

Shows programmatic execution and a void annotation around an application input port.

Run from this directory against the source checkout:

```bash
./gradlew bootRun -PonceSource=../..
./gradlew test -PonceSource=../..
```

Omit `-PonceSource` to consume the version in the shared example catalog from Maven local/Central.
The application explicitly enables Spring SQL initialization for its sample migrations in
`src/main/resources/db/migration`. These scripts are application-owned; Once performs validation
only and never creates tables. Production applications should apply equivalent migrations through
their deployment/migration process. The smoke test uses in-memory H2 and needs no Docker.

Programmatic callbacks return the business value directly; Once supplies `Executed` or `Duplicate`:

```kotlin
val result = once.execute(request) {
    createOrderUseCase.create(command)
}
```

Use `executeWithoutResult` when the caller needs no business value:

```kotlin
val result = once.executeWithoutResult(request) {
    createOrderUseCase.create(command)
}
println("Executed: ${result.wasExecuted()}")
```

These are alternative calls for a request. Each action runs only for a new identity, and the
result is returned after transaction completion. An executed business value may be null;
a duplicate contains identity metadata and never replays a previous value.
Annotated value-returning methods still return `IdempotencyResult.executed(value)` explicitly.

`DemoApplication` uses constructor injection and `CommandLineRunner` to submit the same command
twice. `ProgrammaticOrderHandler` injects `IdempotencyOperations` and the application input port;
its callback returns the order directly. `IdempotentOrderHandler` shows the annotation alternative.
The `main` function only starts Spring Boot.
