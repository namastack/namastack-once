package io.namastack.once.config

import io.namastack.once.DefaultIdempotencyOperations
import io.namastack.once.IdempotencyKeyNormalizer
import io.namastack.once.IdempotencyMarkerRepository
import io.namastack.once.IdempotencyOperations
import io.namastack.once.MongoIdempotencyMarkerRepository
import io.namastack.once.instrumentation.OnceInstrumentation
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.time.Clock

/**
 * Composes MongoDB persistence and Once-owned execution using native MongoDB transactions.
 *
 * Supplies a native manager when the application has none. Existing Once beans replace this default
 * configuration. JDBC takes precedence when both adapters and a datasource are present; applications
 * can compose explicit Once beans to select MongoDB or use both stores.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration(
    after = [OnceCoreInfrastructureAutoConfiguration::class],
    afterName = [
        "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration",
        "io.namastack.once.config.JdbcOnceAutoConfiguration",
    ],
    before = [OnceCoreAnnotationAutoConfiguration::class, OnceCoreThreadingAutoConfiguration::class],
)
@ConditionalOnClass(MongoTemplate::class, MongoTransactionManager::class)
@ConditionalOnBean(MongoTemplate::class)
@ConditionalOnMissingBean(IdempotencyOperations::class)
@ConditionalOnProperty(name = ["namastack.once.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OnceProperties::class, OnceMongoProperties::class)
@EnableTransactionManagement(proxyTargetClass = true)
class MongoOnceAutoConfiguration {
    /**
     * Supplies native MongoDB transactions when no MongoDB manager is configured.
     *
     * @param mongoTemplate Template whose database factory binds marker and business operations.
     * @return The default native transaction manager.
     */
    @Bean
    @ConditionalOnMissingBean(MongoTransactionManager::class)
    fun onceMongoTransactionManager(mongoTemplate: MongoTemplate): MongoTransactionManager =
        MongoTransactionManager(mongoTemplate.mongoDatabaseFactory)

    /**
     * Creates the marker repository using the configured collection and selected native manager.
     *
     * @param instrumentations Ordered execution and cleanup instrumentation.
     * @param beanFactory Resolves the named or unambiguous MongoDB transaction manager.
     * @param mongoTemplate Template sharing the business transaction's database factory.
     * @param onceProperties Transaction-manager selection for the default instance.
     * @param mongoProperties Marker collection settings supplied by the application.
     * @return The repository used unless the application supplies its own.
     */
    @Bean
    @ConditionalOnMissingBean(IdempotencyMarkerRepository::class)
    fun onceMarkerRepository(
        instrumentations: ObjectProvider<OnceInstrumentation>,
        beanFactory: ConfigurableListableBeanFactory,
        mongoTemplate: MongoTemplate,
        onceProperties: OnceProperties,
        mongoProperties: OnceMongoProperties,
    ): IdempotencyMarkerRepository =
        MongoIdempotencyMarkerRepository(
            mongoTemplate,
            transactionManager(beanFactory, onceProperties),
            mongoProperties.collectionName,
            OnceInstrumentation.compose(instrumentations.orderedStream().toList()),
        )

    /**
     * Creates the default MongoDB execution service with the shared key conversion and clock.
     *
     * @param instrumentations Ordered execution and cleanup instrumentation.
     * @param beanFactory Resolves the named or unambiguous native transaction manager.
     * @param repository Fixed persistence implementation for this instance.
     * @param onceProperties Transaction-manager selection for the default instance.
     * @param keyNormalizer Converts application keys to stable strings.
     * @param clock Supplies reservation timestamps.
     * @return The default MongoDB-backed Once instance.
     */
    @Bean
    fun once(
        instrumentations: ObjectProvider<OnceInstrumentation>,
        beanFactory: ConfigurableListableBeanFactory,
        repository: IdempotencyMarkerRepository,
        onceProperties: OnceProperties,
        keyNormalizer: IdempotencyKeyNormalizer,
        clock: Clock,
    ): DefaultIdempotencyOperations =
        DefaultIdempotencyOperations(
            transactionManager(beanFactory, onceProperties),
            repository,
            keyNormalizer,
            clock,
            OnceInstrumentation.compose(instrumentations.orderedStream().toList()),
        )

    private fun transactionManager(
        beanFactory: ConfigurableListableBeanFactory,
        onceProperties: OnceProperties,
    ): MongoTransactionManager =
        if (onceProperties.transactionManager.isEmpty()) {
            beanFactory.resolveNamedBean(MongoTransactionManager::class.java).beanInstance
        } else {
            beanFactory.getBean<MongoTransactionManager>(onceProperties.transactionManager)
        }
}
