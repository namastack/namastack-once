package io.namastack.once.annotation

import org.springframework.aop.support.StaticMethodMatcherPointcut
import java.lang.reflect.Method

/**
 * Selects method declarations with direct, composed, or interface idempotency annotations.
 *
 * Signature validation belongs to the method resolver so invalid declarations fail bean creation
 * instead of being excluded from the pointcut and silently executing unprotected.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class IdempotentMethodPointcut : StaticMethodMatcherPointcut() {
    /**
     * Checks whether a method has a discoverable idempotency declaration.
     *
     * @param method Candidate method exposed by Spring's proxy infrastructure.
     * @param targetClass Concrete application class used for merged-annotation lookup.
     * @return True when the method must be validated and protected by Once.
     */
    override fun matches(
        method: Method,
        targetClass: Class<*>,
    ): Boolean = IdempotentAnnotationResolver.find(method, targetClass) != null
}
