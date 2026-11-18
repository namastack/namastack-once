# Namastack Once custom schema and table example

Uses `idempotency.processed_event` instead of the default `once_marker` table. Both the
programmatic API and `@Idempotent` use the same configured table and share the business transaction.

```yaml
namastack:
  once:
    jdbc:
      schema-name: idempotency
      table-name: processed_event
      schema-initialization:
        enabled: false
```

Custom names require automatic Once schema initialization to be disabled. This example enables
Spring SQL initialization to create the schema, marker table, expiry index, and sample business table
from `src/main/resources/db/schema.sql`. The marker columns and primary key preserve Once's required
structure. Business writes use `demo_order` in the default schema; marker writes use the configured
`idempotency` schema on the same datasource. Applications can also manage these tables with Flyway
or Liquibase.

Run from this directory against the source checkout:

```bash
./gradlew bootRun -PonceSource=../..
./gradlew test -PonceSource=../..
```

The application submits the same command twice and logs `true` for the first execution and `false`
for the duplicate. The smoke tests verify that both APIs write one business row and one marker to the
custom table, and that the default marker table is not created.

The example uses in-memory H2 and needs no Docker. Omit `-PonceSource` to consume the version in the
shared example catalog from Maven local/Central. Restarting the application clears the in-memory data.
