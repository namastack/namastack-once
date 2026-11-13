package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.Properties
import java.util.UUID
import javax.sql.DataSource

@Tag("database")
@Testcontainers
class PostgresOnceIntegrationTest : AbstractOnceIntegrationTest() {
    override val database: String = "postgresql"

    override fun createDataSource(): DataSource =
        DriverManagerDataSource(container.jdbcUrl, container.username, container.password)

    @Test
    fun `reservations follow search path and explicit schemas`() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val firstSchema = "first_$suffix"
        val markerSchema = "markers_$suffix"
        jdbc.execute("CREATE SCHEMA $firstSchema")
        jdbc.execute("CREATE SCHEMA $markerSchema")
        try {
            jdbc.execute("CREATE TABLE $markerSchema.path_marker (LIKE public.once_marker INCLUDING ALL)")
            val pathDataSource =
                DriverManagerDataSource(container.jdbcUrl, container.username, container.password).apply {
                    connectionProperties =
                        Properties().apply {
                            setProperty("currentSchema", "$firstSchema,$markerSchema,public")
                        }
                }
            val pathManager = JdbcTransactionManager(pathDataSource)
            val repository = createTestRepository(pathDataSource, pathManager, "path_marker")
            val pathOnce =
                DefaultIdempotencyOperations(
                    pathManager,
                    repository,
                    IdempotencyKeyNormalizer(),
                    clock,
                )
            val identity = IdempotencyRequest.of("search-path", "one")
            assertThat(pathOnce.execute(identity) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
            assertThat(pathOnce.execute<Void?>(identity) { error("duplicate must skip") }.wasExecuted()).isFalse()
            assertThat(JdbcTemplate(pathDataSource).queryForObject("SELECT COUNT(*) FROM path_marker", Int::class.java))
                .isEqualTo(1)

            jdbc.execute("CREATE TABLE $firstSchema.path_marker (LIKE public.once_marker INCLUDING ALL)")
            val explicitRepository =
                createTestRepository(pathDataSource, pathManager, "path_marker", markerSchema)
            val explicitOnce =
                DefaultIdempotencyOperations(
                    pathManager,
                    explicitRepository,
                    IdempotencyKeyNormalizer(),
                    clock,
                )
            assertThat(
                explicitOnce.execute<Void?>(identity) { error("explicit schema must find the marker") }.wasExecuted(),
            ).isFalse()
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM $firstSchema.path_marker", Int::class.java)).isZero()
        } finally {
            jdbc.execute("DROP SCHEMA $firstSchema CASCADE")
            jdbc.execute("DROP SCHEMA $markerSchema CASCADE")
        }
    }

    @Test
    fun `partitioned cleanup respects the batch limit and preserves retained markers`() {
        jdbc.execute(
            """
            CREATE TABLE partitioned_marker (
                idempotency_hash CHAR(64) PRIMARY KEY,
                scope VARCHAR(200) NOT NULL,
                created_at TIMESTAMP(6) NOT NULL,
                expires_at TIMESTAMP(6)
            ) PARTITION BY RANGE (idempotency_hash)
            """.trimIndent(),
        )
        try {
            jdbc.execute("CREATE TABLE marker_a PARTITION OF partitioned_marker FOR VALUES FROM (MINVALUE) TO ('8')")
            jdbc.execute("CREATE TABLE marker_b PARTITION OF partitioned_marker FOR VALUES FROM ('8') TO (MAXVALUE)")
            jdbc.update(
                "INSERT INTO partitioned_marker VALUES (?, 'scope', CURRENT_TIMESTAMP, TIMESTAMP '2000-01-01')",
                "0".repeat(64),
            )
            jdbc.update("INSERT INTO partitioned_marker VALUES (?, 'scope', CURRENT_TIMESTAMP, NULL)", "f".repeat(64))
            jdbc.update(
                "INSERT INTO partitioned_marker VALUES (?, 'scope', CURRENT_TIMESTAMP, TIMESTAMP '2100-01-01')",
                "e".repeat(64),
            )
            val repository =
                createTestRepository(
                    dataSource,
                    manager,
                    tableName = "partitioned_marker",
                )
            val cutoff = Instant.parse("2026-01-01T00:00:00Z")

            assertThat(repository.cleanup(cutoff, 1)).isEqualTo(1)
            assertThat(jdbc.queryForList("SELECT idempotency_hash FROM partitioned_marker", String::class.java))
                .containsExactlyInAnyOrder("e".repeat(64), "f".repeat(64))
            assertThat(repository.cleanup(cutoff, 1)).isZero()
        } finally {
            jdbc.execute("DROP TABLE partitioned_marker CASCADE")
        }
    }

    companion object {
        @Container
        @JvmStatic
        val container = PostgreSQLContainer("postgres:18-alpine")
    }
}
