package io.namastack.once

import com.mongodb.MongoException
import com.mongodb.client.MongoClients
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Clock

@Tag("database")
@Testcontainers
class MongoStandaloneIntegrationTest {
    @Test
    fun `standalone MongoDB rejects protected work without leaving nontransactional markers`() {
        MongoClients.create(container.connectionString).use { client ->
            val factory = SimpleMongoClientDatabaseFactory(client, "once_standalone")
            val mongo = MongoTemplate(factory)
            mongo.createCollection("once_marker")
            val manager = MongoTransactionManager(factory)
            val repository = MongoIdempotencyMarkerRepository(mongo, manager, "once_marker")
            val once = DefaultIdempotencyOperations(manager, repository, IdempotencyKeyNormalizer(), Clock.systemUTC())
            var actionExecuted = false

            assertThatThrownBy {
                once.execute(IdempotencyRequest.of("standalone", "key")) {
                    actionExecuted = true
                    IdempotencyResult.executed()
                }
            }.satisfies({ failure ->
                val mongoFailure =
                    generateSequence(
                        failure,
                    ) { it.cause }.filterIsInstance<MongoException>().firstOrNull()
                assertThat(mongoFailure).isNotNull()
                // The driver may reject unsupported sessions before the server receives a transaction command.
                assertThat(mongoFailure!!.code).isIn(20, -4)
            })

            assertThat(actionExecuted).isFalse()
            assertThat(mongo.count(Query(), Document::class.java, "once_marker")).isZero()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val container = MongoDBContainer("mongo:9.0.4")
    }
}
