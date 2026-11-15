# Namastack Once postgresql example

Shows programmatic execution and a void annotation around an application input port.

Run from this directory against the source checkout:

```bash
docker compose up -d
./gradlew bootRun -PonceSource=../..
./gradlew test -PonceSource=../..
```

Omit `-PonceSource` to consume the version in the shared example catalog from Maven local/Central.
The application explicitly enables Spring SQL initialization for its sample migrations in
`src/main/resources/db/migration`. These scripts are application-owned; Once performs validation
only and never creates tables. Production applications should apply equivalent migrations through
their deployment/migration process. The smoke test starts its own PostgreSQL container and requires Docker.

`DemoApplication` uses constructor injection and `CommandLineRunner` to submit the same command
twice. `ProgrammaticOrderHandler` injects `IdempotencyOperations` and the application input port;
its callback returns the order directly. `IdempotentOrderHandler` shows the annotation alternative.
The `main` function only starts Spring Boot.
