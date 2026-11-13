package io.namastack.once

import io.namastack.once.config.JdbcDatabaseType
import io.namastack.once.query.JdbcOnceMarkerQueriesFactory
import org.springframework.jdbc.core.JdbcTemplate
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

    fun effects(): Int = checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM business_effect", Int::class.java))

    fun markers(): Int = checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM once_marker", Int::class.java))
}

fun createTestRepository(
    dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
    tableName: String = "once_marker",
    schemaName: String? = null,
): IdempotencyMarkerRepository {
    val qualifiedName = schemaName?.let { "$it.$tableName" } ?: tableName
    return JdbcIdempotencyMarkerRepository(dataSource, transactionManager) {
        JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.detect(dataSource), qualifiedName)
    }
}
