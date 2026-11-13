package io.namastack.once.config

import io.namastack.once.annotation.IdempotentBeanPostProcessor
import io.namastack.once.annotation.IdempotentMethodInterceptor
import io.namastack.once.annotation.IdempotentMethodResolver
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Role

/**
 * Adapts annotated methods to the selected, preconfigured Once Spring bean.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration(after = [OnceCoreInfrastructureAutoConfiguration::class])
@ConditionalOnProperty(name = ["namastack.once.enabled"], havingValue = "true", matchIfMissing = true)
class OnceCoreAnnotationAutoConfiguration {
    /**
     * Creates the metadata resolver that validates annotated method declarations.
     *
     * @param beanFactory Registry for named policies and transaction configuration.
     * @return The shared annotation metadata resolver.
     */
    @Bean
    fun idempotentMethodResolver(beanFactory: ConfigurableListableBeanFactory): IdempotentMethodResolver =
        IdempotentMethodResolver(beanFactory)

    /**
     * Creates the adapter that delegates protected method invocations to the execution service.
     *
     * @param methodResolver Supplies validated metadata for the invoked method.
     * @param beanFactory Resolves Spring bean references inside key expressions.
     * @return The method interceptor attached to annotated application beans.
     */
    @Bean
    fun idempotentMethodInterceptor(
        methodResolver: IdempotentMethodResolver,
        beanFactory: ConfigurableListableBeanFactory,
    ): IdempotentMethodInterceptor =
        IdempotentMethodInterceptor(
            methodResolver = methodResolver,
            beanFactory = beanFactory,
        )

    companion object {
        /**
         * Registers the annotation post-processor without eagerly creating application dependencies.
         *
         * Static registration and lazy suppliers keep datasource and policy initialization out of
         * Spring's early post-processor discovery phase.
         *
         * @param beanFactory Registry queried only when interceptor or validation services are needed.
         * @return The infrastructure post-processor responsible for protected bean proxies.
         */
        @Bean
        @JvmStatic
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        fun idempotentBeanPostProcessor(beanFactory: ConfigurableListableBeanFactory): IdempotentBeanPostProcessor =
            IdempotentBeanPostProcessor(
                interceptorSupplier = { beanFactory.getBean<IdempotentMethodInterceptor>() },
                methodResolverSupplier = { beanFactory.getBean<IdempotentMethodResolver>() },
            )
    }
}
