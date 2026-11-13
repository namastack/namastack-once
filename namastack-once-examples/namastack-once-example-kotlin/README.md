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
