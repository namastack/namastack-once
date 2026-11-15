# Namastack Once java example

Shows a Java result-returning annotation around an application input port.

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

The programmatic API also accepts ordinary Java callbacks:

```java
var result = once.execute(request, () -> createOrderUseCase.create(command));
```

For actions without a business value, use a `Runnable` instead:

```java
var result = once.executeWithoutResult(request, () -> {
    createOrderUseCase.create(command);
});
```

Both return `IdempotencyResult`: fresh work produces `Executed` after commit; duplicates skip
work and return `Duplicate` without replaying a value. These are alternative calls for a request.
The annotated handler continues to return `IdempotencyResult.executed(...)` explicitly.

`DemoApplication` receives `IdempotentOrderHandler` through its constructor and runs the demo
through `CommandLineRunner`. The `main` method only starts Spring Boot.
