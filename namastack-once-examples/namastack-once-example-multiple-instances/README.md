# Multiple Once instances

Two fixed Once beans use the same transaction manager and datasource with different marker tables:
`ordersOnce` uses `orders_once`, and `billingOnce` uses `billing_once`.

`OnceConfiguration` shows the two construction steps explicitly:

1. Application configuration constructs `JdbcIdempotencyMarkerRepository` with the datasource, matching manager and a lazy SQL supplier for the selected table.
2. `DefaultIdempotencyOperations` combines that repository with the matching `PlatformTransactionManager`.

The factory belongs to JDBC and creates persistence only. The execution implementation belongs to
Core; Spring configuration composes both into an `IdempotencyOperations` bean. The `@Primary`
instance is used by an unqualified `@Idempotent`; `operationsBean = "billingOnce"` selects the other bean.
Programmatic callers select their instance through constructor injection with `@Qualifier`.

The application owns `schema.sql` and enables Spring SQL initialization. `@DependsOnDatabaseInitialization`
ensures repository construction runs after that schema exists. Once itself never creates tables.
Both instances participate in the shared Core cleanup scheduler.

```bash
./gradlew bootRun -PonceSource=../..
./gradlew test -PonceSource=../..
```

For separate databases, pass each repository factory its datasource and matching transaction manager.
Use that same manager for its execution template.
The Once API and annotation selection stay the same.
