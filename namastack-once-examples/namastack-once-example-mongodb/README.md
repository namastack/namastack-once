# Namastack Once MongoDB example

Demonstrates the programmatic API, an annotated message adapter, and an application service joining
the Once-owned MongoDB transaction. The business document and marker commit or roll back together.

Start the local single-node replica set, then run the application:

```bash
docker compose up -d --wait
./gradlew bootRun -PonceSource=../..
```

The application processes one request twice and prints one execution and one duplicate. The
application-owned `MongoSchemaConfiguration` provisions the demo collections and expiry index.
Once never runs collection initialization scripts automatically.

Run the three smoke tests against a separate Testcontainers replica set:

```bash
./gradlew test -PonceSource=../..
```

Docker is required. No local MongoDB instance or running Compose service is needed for the tests.
Stop the local demo database with `docker compose down` when finished.

Use the same `MongoDatabaseFactory` for the transaction manager, marker repository, and business
operations. This example stores marker timestamps as BSON Int64 epoch microseconds rather than BSON
dates. Keep the supplied representation and expiry index; cleanup is handled by Once rather than a
MongoDB TTL index. Production deployments require a transaction-capable replica set or sharded
cluster. MongoDB write conflicts remain technical failures for the caller to retry.
