package io.namastack.once.annotation

import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor
import org.springframework.aop.support.AopUtils
import org.springframework.aop.support.DefaultPointcutAdvisor
import java.util.function.Supplier

/**
 * Validates annotated beans and adds Once advice behind existing Spring advice.
 *
 * Class-based proxies require open application classes and methods. Infrastructure suppliers are
 * resolved lazily so registering this post-processor does not initialize datasources prematurely.
 *
 * @param interceptorSupplier Supplies the shared annotation interceptor after infrastructure registration.
 * @param methodResolverSupplier Supplies the method validator used before annotated beans become available.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class IdempotentBeanPostProcessor(
    interceptorSupplier: Supplier<IdempotentMethodInterceptor>,
    private val methodResolverSupplier: Supplier<IdempotentMethodResolver>,
) : AbstractBeanFactoryAwareAdvisingPostProcessor() {
    init {
        advisor =
            DefaultPointcutAdvisor(
                IdempotentMethodPointcut(),
                MethodInterceptor { invocation -> interceptorSupplier.get().invoke(invocation) },
            )
        isProxyTargetClass = true
    }

    /**
     * Validates discovered declarations before exposing the bean through idempotency advice.
     *
     * @param bean Initialized application bean, possibly already wrapped by another proxy.
     * @param beanName Spring bean name associated with this instance.
     * @return The bean with Once advice when annotated methods are present.
     * @throws IllegalArgumentException if a discovered annotation cannot be applied safely.
     */
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        val targetClass = AopUtils.getTargetClass(bean)

        if (IdempotentAnnotationResolver.hasAnnotatedMethods(targetClass)) {
            methodResolverSupplier.get().validateClass(targetClass)
        }

        return super.postProcessAfterInitialization(bean, beanName)
    }
}
