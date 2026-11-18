package io.namastack.once

import io.namastack.once.config.JdbcDatabaseType
import io.namastack.once.query.JdbcOnceMarkerQueriesFactory
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

/** Database state and business-write helpers shared by the database test contracts. */
interface OnceDatabaseTestSupport {
    val dataSource: DataSource
    val jdbc: JdbcTemplate
    val manager: PlatformTransactionManager
    val markerRepository: IdempotencyMarkerRepository
    val once: DefaultIdempotencyOperations
    val clock: MutableOnceClock

    fun request(key: String): IdempotencyRequest = IdempotencyRequest.of("test.operation", key)

    fun effect(id: String) {
        jdbc.update("INSERT INTO business_effect (id, amount) VALUES (?, ?)", id, 1)
    }

    fun effects(): Int = checkNotNull(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM business_effect"))

    fun markers(): Int = checkNotNull(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM once_marker"))
}

fun createTestRepository(
    dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
    tableName: String = "once_marker",
    schemaName: String? = null,
): IdempotencyMarkerRepository {
    val qualifiedName = schemaName?.let { "$it.$tableName" } ?: tableName
    return JdbcIdempotencyMarkerRepository(dataSource, transactionManager) {
        JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.from(dataSource), qualifiedName)
    }
}

/** Applies the marker schema once, using the dialect's script and statement separator. */
fun initializeTestMarkerSchema(dataSource: DataSource) {
    val databaseType = JdbcDatabaseType.from(dataSource)
    val script = DefaultResourceLoader().getResource(databaseType.schemaLocation)
    ResourceDatabasePopulator(script)
        .apply {
            setSeparator(databaseType.statementSeparator)
        }.execute(dataSource)
}
