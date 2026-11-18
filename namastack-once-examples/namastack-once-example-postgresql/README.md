# Namastack Once postgresql example

Shows programmatic execution and a void annotation around an application input port.

Run from this directory against the source checkout:

```bash
docker compose up -d
./gradlew bootRun -PonceSource=../..
./gradlew test -PonceSource=../..
```

Omit `-PonceSource` to consume the version in the shared example catalog from Maven local/Central.
Once automatically creates its marker table and expiry index at startup. Spring SQL initialization
creates the sample business table using the script in `src/main/resources/db/migration`.
The smoke test starts its own PostgreSQL container and requires Docker.

`DemoApplication` uses constructor injection and `CommandLineRunner` to submit the same command
twice. `ProgrammaticOrderHandler` injects `IdempotencyOperations` and the application input port;
its callback returns the order directly. `IdempotentOrderHandler` shows the annotation alternative.
The `main` function only starts Spring Boot.
