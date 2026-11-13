package io.namastack.once.annotation

import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.BeanFactory
import org.springframework.context.expression.BeanFactoryResolver
import org.springframework.context.expression.MethodBasedEvaluationContext
import org.springframework.core.DefaultParameterNameDiscoverer

/**
 * Adapts annotated methods to the programmatic idempotency execution lifecycle.
 *
 * The interceptor resolves a key and delegates transaction ownership to [io.namastack.once.IdempotencyOperations].
 * Void methods discard that normal result; result-returning methods expose the same flat execution
 * or duplicate outcome as programmatic calls. Application exceptions propagate through the core.
 *
 * @param methodResolver Provides validated metadata for the concrete target method.
 * @param beanFactory Resolves beans referenced by key expressions.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class IdempotentMethodInterceptor(
    private val methodResolver: IdempotentMethodResolver,
    private val beanFactory: BeanFactory,
) : MethodInterceptor {
    private val parameterNameDiscoverer = DefaultParameterNameDiscoverer()

    /**
     * Executes an annotated invocation through the shared idempotency service.
     *
     * @param invocation Current proxy invocation, including its target and arguments.
     * @return The core result for result-returning methods, or null for void methods.
     * @throws IllegalArgumentException if the resolved key is null.
     * @throws IllegalStateException if the method supplies an invalid result wrapper.
     */
    override fun invoke(invocation: MethodInvocation): Any? {
        val target = checkNotNull(invocation.`this`)
        val targetClass = AopUtils.getTargetClass(target)
        val methodMetadata = methodResolver.resolve(invocation.method, targetClass)
        val invocationKey = resolveInvocationKey(invocation, target, methodMetadata)
        val request =
            IdempotencyRequest
                .of(methodMetadata.scope, invocationKey)
                .withRetentionPolicy(methodMetadata.retentionPolicy)

        val executionResult =
            methodMetadata.operations.execute(request) {
                invokeProtectedMethod(invocation, methodMetadata)
            }

        return if (methodMetadata.returnsVoid) null else executionResult
    }

    private fun resolveInvocationKey(
        invocation: MethodInvocation,
        target: Any,
        methodMetadata: IdempotentMethodMetadata,
    ): Any {
        val keyExpression = methodMetadata.keyExpression
        val invocationKey =
            if (keyExpression != null) {
                // Expressions are cached; arguments and bean references belong to this invocation.
                val evaluationContext =
                    MethodBasedEvaluationContext(
                        target,
                        methodMetadata.method,
                        invocation.arguments,
                        parameterNameDiscoverer,
                    ).apply { beanResolver = BeanFactoryResolver(beanFactory) }
                keyExpression.getValue(evaluationContext)
            } else {
                val parameterIndex = checkNotNull(methodMetadata.keyParameterIndex)
                invocation.arguments[parameterIndex]
            }

        return requireNotNull(invocationKey) { "Idempotency key must not be null" }
    }

    private fun invokeProtectedMethod(
        invocation: MethodInvocation,
        methodMetadata: IdempotentMethodMetadata,
    ): IdempotencyResult<Any?> {
        val methodResult = invocation.proceed()

        if (methodMetadata.returnsVoid) return IdempotencyResult.executed(null)

        check(methodResult is IdempotencyResult<*>) { "Annotated method must return a non-null IdempotencyResult" }

        @Suppress("UNCHECKED_CAST")
        return methodResult as IdempotencyResult<Any?>
    }
}
