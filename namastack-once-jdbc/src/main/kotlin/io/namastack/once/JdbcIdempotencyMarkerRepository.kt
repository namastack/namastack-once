package io.namastack.once

import io.namastack.once.instrumentation.OnceInstrumentation
import io.namastack.once.query.JdbcOnceMarkerQueries
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Types.TIMESTAMP
import java.time.Instant
import javax.sql.DataSource

/**
 * Persists markers through JDBC on the transaction-bound business connection.
 *
 * Duplicates require a full transaction rollback.
 * Existing markers protect their identities until cleanup deletes them. The application must
 * preserve the supplied schema so only an identity collision can cause a uniqueness conflict.
 * Application configuration supplies SQL; successful initialization is cached on first use.
 *
 * @param dataSource Datasource shared by marker reservations and business writes.
 * @param transactionManager Matching local manager used to start independent cleanup transactions.
 * @param instrumentation Observes independent cleanup batches through commit.
 * @param queries Supplies statements for the configured database and marker table on first use.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class JdbcIdempotencyMarkerRepository(
    dataSource: DataSource,
    transactionManager: PlatformTransactionManager,
    private val instrumentation: OnceInstrumentation,
    queries: () -> JdbcOnceMarkerQueries,
) : IdempotencyMarkerRepository {
    /**
     * Creates JDBC persistence without additional instrumentation.
     * @param dataSource Datasource shared with business writes.
     * @param transactionManager Matching local manager for independent cleanup transactions.
     * @param queries Lazily supplies the configured marker statements.
     */
    constructor(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        queries: () -> JdbcOnceMarkerQueries,
    ) : this(dataSource, transactionManager, OnceInstrumentation.NOOP, queries)

    // Match Spring's transaction managers and avoid a separate connection binding for the proxy.
    private val transactionDataSource =
        (dataSource as? TransactionAwareDataSourceProxy)?.targetDataSource ?: dataSource

    /**
     * Exposes the canonical datasource binding used for Core transaction participation checks.
     *
     * Transaction-aware datasource proxies use their target. Infrastructure proxies are unwrapped
     * with the same rules used by Spring transactions.
     */
    override val transactionResourceKey: Any =
        TransactionSynchronizationUtils.unwrapResourceIfNecessary(transactionDataSource)

    private val jdbcClient = JdbcClient.create(transactionDataSource)
    private val queries by lazy(queries)
    private val cleanupTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = PROPAGATION_REQUIRES_NEW
        }

    /**
     * Inserts a new marker or skips an existing identity in the current transaction.
     *
     * Existing markers protect their identities until cleanup deletes them. Once commits the
     * reservation with business changes, or rolls back the entire transaction on a duplicate
     * or failure. A false result must be followed by rollback, with no further work in the transaction.
     *
     * @param marker Identity and retention window requested by the current invocation.
     * @return True when inserted, or false when the identity already exists.
     * @throws IllegalStateException if the matching Spring transaction is not active.
     * @throws org.springframework.dao.DataAccessException if reservation fails for another reason.
     */
    override fun reserve(marker: IdempotencyMarker): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Once reservation requires an active transaction"
        }
        validateResourceBinding()

        return try {
            jdbcClient
                .sql(queries.insertMarker)
                .param("keyHash", marker.keyHash)
                .param("scope", marker.scope)
                .param("createdAt", marker.createdAt.toUtcTimestamp(), TIMESTAMP)
                .param("expiresAt", marker.expiresAt?.toUtcTimestamp(), TIMESTAMP)
                .update()
            true
        } catch (_: DuplicateKeyException) {
            false
        }
    }

    /**
     * Deletes one bounded batch of eligible markers in a single database statement.
     *
     * The database-specific statement applies the cutoff and batch limit atomically. No candidate
     * list is loaded into application memory and no separate per-marker DELETE commands are issued.
     * Each batch runs in its own REQUIRES_NEW transaction. The returned count represents committed
     * deletions; commit failures propagate while an enclosing business transaction remains separate.
     *
     * @param cutoff Latest expiry eligible for deletion, inclusive.
     * @param batchSize Maximum number of markers deleted by this statement.
     * @return Number of deleted rows, zero when the statement finds no eligible work.
     * @throws IllegalArgumentException if the batch limit is not positive.
     * @throws IllegalStateException if the manager does not bind an independent transaction to the configured datasource.
     */
    override fun cleanup(
        cutoff: Instant,
        batchSize: Int,
    ): Int =
        instrumentation.cleanup("jdbc", cutoff, batchSize) {
            require(batchSize > 0) { "Cleanup batch size must be positive" }

            val enclosingResource = TransactionSynchronizationManager.getResource(transactionResourceKey)
            cleanupTransaction.execute {
                validateResourceBinding()
                check(TransactionSynchronizationManager.getResource(transactionResourceKey) !== enclosingResource) {
                    "Once cleanup requires an independent transaction bound to the configured resource"
                }
                jdbcClient
                    .sql(queries.deleteExpiredBatch)
                    .param("cutoff", cutoff.toUtcTimestamp(), TIMESTAMP)
                    .param("batchSize", batchSize)
                    .update()
            }
        }

    private fun validateResourceBinding() {
        check(TransactionSynchronizationManager.hasResource(transactionResourceKey)) {
            "Once persistence requires the matching Spring-bound transaction"
        }
    }
}
