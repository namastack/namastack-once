package io.namastack.demo

import io.namastack.once.DefaultIdempotencyOperations
import io.namastack.once.IdempotencyKeyNormalizer
import io.namastack.once.IdempotencyOperations
import io.namastack.once.JdbcIdempotencyMarkerRepository
import io.namastack.once.config.JdbcDatabaseType
import io.namastack.once.query.JdbcOnceMarkerQueriesFactory
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import javax.sql.DataSource

/**
 * Composes JDBC persistence and Core execution into two named Once beans.
 *
 * Each bean method supplies a JDBC marker repository and its matching local transaction manager
 * to Core after application-managed schema initialization. Once starts and owns each transaction.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Configuration(proxyBeanMethods = false)
class OnceConfiguration {
    /**
     * Creates the default instance used by unqualified annotation declarations.
     *
     * @param dataSource Datasource shared with the application's business writes.
     * @param transactionManager Manager shared with the application's business writes.
     * @param keyNormalizer Shared key normalizer provided by Once infrastructure.
     * @param clock Shared clock used for reservation and cleanup timestamps.
     * @return Fixed instance storing order markers in orders_once.
     */
    @Bean
    @Primary
    @DependsOnDatabaseInitialization
    fun ordersOnce(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        keyNormalizer: IdempotencyKeyNormalizer,
        clock: Clock,
    ): IdempotencyOperations {
        val repository = JdbcIdempotencyMarkerRepository(dataSource, transactionManager) {
            JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.detect(dataSource), "orders_once")
        }
        return DefaultIdempotencyOperations(transactionManager, repository, keyNormalizer, clock)
    }

    /**
     * Creates the instance selected explicitly by billing consumers.
     *
     * @param dataSource Datasource shared with the application's business writes.
     * @param transactionManager Manager shared with the application's business writes.
     * @param keyNormalizer Shared key normalizer provided by Once infrastructure.
     * @param clock Shared clock used for reservation and cleanup timestamps.
     * @return Fixed instance storing billing markers in billing_once.
     */
    @Bean
    @DependsOnDatabaseInitialization
    fun billingOnce(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        keyNormalizer: IdempotencyKeyNormalizer,
        clock: Clock,
    ): IdempotencyOperations {
        val repository = JdbcIdempotencyMarkerRepository(dataSource, transactionManager) {
            JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.detect(dataSource), "billing_once")
        }
        return DefaultIdempotencyOperations(transactionManager, repository, keyNormalizer, clock)
    }
}
