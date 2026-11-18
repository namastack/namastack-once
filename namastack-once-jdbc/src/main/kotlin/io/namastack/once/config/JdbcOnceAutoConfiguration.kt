package io.namastack.once.config

import io.namastack.once.DefaultIdempotencyOperations
import io.namastack.once.DefaultJdbcTableNameResolver
import io.namastack.once.IdempotencyKeyNormalizer
import io.namastack.once.IdempotencyMarkerRepository
import io.namastack.once.IdempotencyOperations
import io.namastack.once.JdbcIdempotencyMarkerRepository
import io.namastack.once.JdbcTableNameResolver
import io.namastack.once.instrumentation.OnceInstrumentation
import io.namastack.once.query.JdbcOnceMarkerQueriesFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import javax.sql.DataSource

/**
 * Creates the default fixed JDBC Once instance after database schema initialization.
 *
 * Applications defining their own Once beans replace the default instance. Additional instances
 * supply the repository SQL through a lazy query supplier. This configuration owns datasource,
 * transaction-manager, table-name and SQL-dialect resolution.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration(
    after = [OnceCoreInfrastructureAutoConfiguration::class],
    before = [OnceCoreAnnotationAutoConfiguration::class, OnceCoreThreadingAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@ConditionalOnClass(DataSource::class, JdbcClient::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(name = ["namastack.once.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(IdempotencyOperations::class)
@EnableConfigurationProperties(OnceProperties::class, JdbcOnceConfigurationProperties::class)
class JdbcOnceAutoConfiguration {
    /**
     * Creates the table name resolver for JDBC operations.
     *
     * @param properties Configuration properties for table prefix and schema
     * @return Table name resolver for constructing fully qualified table names
     * @since 0.2.0
     */
    @Bean
    @ConditionalOnMissingBean
    internal fun jdbcTableNameResolver(properties: JdbcOnceConfigurationProperties): JdbcTableNameResolver =
        DefaultJdbcTableNameResolver(properties)

    /**
     * Creates default JDBC persistence with lazily detected database-specific statements.
     *
     * @param instrumentations Ordered execution and cleanup instrumentation.
     * @param beanFactory Resolves the configured or unambiguous transaction manager.
     * @param dataSource Datasource shared with business writes.
     * @param properties Transaction-manager selection for the default instance.
     * @param tableNameResolver Resolver for fully qualified table names.
     * @return The repository used when the application has not supplied its own.
     */
    @Bean
    @DependsOnDatabaseInitialization
    @ConditionalOnMissingBean(IdempotencyMarkerRepository::class)
    fun onceMarkerRepository(
        instrumentations: ObjectProvider<OnceInstrumentation>,
        beanFactory: ConfigurableListableBeanFactory,
        dataSource: DataSource,
        properties: OnceProperties,
        tableNameResolver: JdbcTableNameResolver,
    ): IdempotencyMarkerRepository =
        JdbcIdempotencyMarkerRepository(
            instrumentation = OnceInstrumentation.compose(instrumentations.orderedStream().toList()),
            dataSource = dataSource,
            transactionManager = transactionManager(beanFactory, properties),
            queries = {
                JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.from(dataSource), tableNameResolver.onceMarker)
            },
        )

    /**
     * Creates the default execution service, which owns each protected invocation's transaction.
     *
     * @param instrumentations Ordered execution and cleanup instrumentation.
     * @param beanFactory Resolves the configured or unambiguous transaction manager.
     * @param repository Fixed persistence implementation for this instance.
     * @param properties Transaction-manager selection for the default instance.
     * @param clock Supplies reservation timestamps.
     * @param keyNormalizer Converts application keys to stable strings.
     * @return The default Once instance.
     */
    @Bean
    @DependsOnDatabaseInitialization
    fun once(
        instrumentations: ObjectProvider<OnceInstrumentation>,
        beanFactory: ConfigurableListableBeanFactory,
        repository: IdempotencyMarkerRepository,
        properties: OnceProperties,
        clock: Clock,
        keyNormalizer: IdempotencyKeyNormalizer,
    ): DefaultIdempotencyOperations =
        DefaultIdempotencyOperations(
            transactionManager = transactionManager(beanFactory, properties),
            repository = repository,
            keyNormalizer = keyNormalizer,
            clock = clock,
            instrumentation = OnceInstrumentation.compose(instrumentations.orderedStream().toList()),
        )

    private fun transactionManager(
        beanFactory: ConfigurableListableBeanFactory,
        properties: OnceProperties,
    ): PlatformTransactionManager =
        if (properties.transactionManager.isEmpty()) {
            beanFactory.resolveNamedBean(PlatformTransactionManager::class.java).beanInstance
        } else {
            beanFactory.getBean<PlatformTransactionManager>(properties.transactionManager)
        }
}
