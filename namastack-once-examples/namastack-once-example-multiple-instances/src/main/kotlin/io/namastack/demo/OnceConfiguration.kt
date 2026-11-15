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

@Configuration(proxyBeanMethods = false)
class OnceConfiguration {
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
