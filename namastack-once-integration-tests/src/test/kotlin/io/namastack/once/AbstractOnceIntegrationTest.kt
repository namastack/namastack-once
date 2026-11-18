package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/** Initializes a fresh schema for every inherited database contract test. */
abstract class AbstractOnceIntegrationTest :
    OnceExecutionContract,
    OnceCleanupContract,
    OnceConcurrencyContract {
    override lateinit var dataSource: DataSource
    override lateinit var jdbc: JdbcTemplate
    override lateinit var manager: PlatformTransactionManager
    override lateinit var markerRepository: IdempotencyMarkerRepository
    override lateinit var once: DefaultIdempotencyOperations
    override lateinit var clock: MutableOnceClock

    protected abstract fun createDataSource(): DataSource

    protected abstract val database: String

    protected open fun createTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        JdbcTransactionManager(dataSource)

    @BeforeEach
    fun prepareDatabase() {
        dataSource = createDataSource()
        jdbc = JdbcTemplate(dataSource)
        resetDatabase()
        manager = createTransactionManager(dataSource)
        clock = MutableOnceClock()
        markerRepository = createTestRepository(dataSource, manager)
        once =
            DefaultIdempotencyOperations(
                manager,
                markerRepository,
                IdempotencyKeyNormalizer(),
                clock,
            )
    }

    @Test
    fun `reinitializing the schema preserves committed markers`() {
        val request = request("schema-reinitialization")
        once.executeWithoutResult(request) { effect("original") }

        initializeTestMarkerSchema(dataSource)

        assertThat(once.executeWithoutResult(request) { error("duplicate must skip") }.wasExecuted()).isFalse()
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    private fun resetDatabase() {
        dropTable("business_effect")
        dropTable("once_marker")
        initializeTestMarkerSchema(dataSource)
        jdbc.execute("CREATE TABLE business_effect (id VARCHAR(100) PRIMARY KEY, amount INTEGER NOT NULL)")
    }

    private fun dropTable(tableName: String) {
        if (database == "oracle") {
            jdbc.execute(
                "BEGIN EXECUTE IMMEDIATE 'DROP TABLE $tableName'; " +
                    "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;",
            )
        } else {
            jdbc.execute("DROP TABLE IF EXISTS $tableName")
        }
    }
}
