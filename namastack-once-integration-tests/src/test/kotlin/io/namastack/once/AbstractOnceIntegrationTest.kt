package io.namastack.once

import org.junit.jupiter.api.BeforeEach
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
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
        listOf("business_effect", "once_marker").forEach { table ->
            if (database == "oracle") {
                jdbc.execute(
                    "BEGIN EXECUTE IMMEDIATE 'DROP TABLE $table'; " +
                        "EXCEPTION WHEN OTHERS THEN IF SQLCODE != -942 THEN RAISE; END IF; END;",
                )
            } else {
                jdbc.execute("DROP TABLE IF EXISTS $table")
            }
        }
        ResourceDatabasePopulator(ClassPathResource("schema/$database.sql")).execute(dataSource)
        jdbc.execute("CREATE TABLE business_effect (id VARCHAR(100) PRIMARY KEY, amount INTEGER NOT NULL)")
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
}
