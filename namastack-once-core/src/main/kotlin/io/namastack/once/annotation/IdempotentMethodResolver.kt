package io.namastack.once.annotation

import io.namastack.once.IdempotencyOperations
import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeanProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.scheduling.annotation.Async
import org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionAttributeSource
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds validated invocation metadata for annotated application methods.
 *
 * Signature and key-source checks run before transaction and retention resolution. Metadata is
 * cached per concrete method and target class so inherited declarations retain their own
 * configuration and expressions are parsed once for each combination.
 *
 * @param beanFactory Registry used to resolve Once instances and retention configuration.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class IdempotentMethodResolver(
    private val beanFactory: ConfigurableListableBeanFactory,
) {
    /**
     * Identifies the method and target class owning one validated metadata entry.
     * Subclasses can inherit the same implementation while declaring different interface annotations.
     * Including the target class keeps their scope, instance selection, and validation independent.
     */
    private data class MethodCacheKey(
        val method: Method,
        val targetClass: Class<*>,
    )

    private val metadataByMethod = ConcurrentHashMap<MethodCacheKey, IdempotentMethodMetadata>()
    private val expressionParser = SpelExpressionParser()
    private val transactionAttributeSource =
        beanFactory.getBeanProvider<TransactionAttributeSource>().ifAvailable
            ?: AnnotationTransactionAttributeSource()
    private val retentionPolicyResolver = IdempotentRetentionPolicyResolver(beanFactory)

    /**
     * Resolves the concrete implementation method and caches its validated metadata.
     *
     * Entries belong to the method and target class together, so inherited implementations do not
     * reuse another class's annotation configuration or bypass its startup validation.
     *
     * @param method Method exposed by the current invocation, possibly declared on an interface.
     * @param targetClass Actual application class behind the Spring proxy.
     * @return Validated configuration used by the annotation interceptor.
     */
    internal fun resolve(
        method: Method,
        targetClass: Class<*>,
    ): IdempotentMethodMetadata {
        val implementationMethod = AopUtils.getMostSpecificMethod(method, targetClass)

        return metadataByMethod.computeIfAbsent(MethodCacheKey(implementationMethod, targetClass)) {
            createMethodMetadata(implementationMethod, targetClass)
        }
    }

    /**
     * Validates all discovered annotated methods before the application accepts invocations.
     *
     * Non-public and final declarations are inspected too, so unproxyable methods cannot silently
     * execute without protection.
     *
     * @param targetClass Application class whose method declarations should be inspected.
     * @throws IllegalArgumentException if a discovered method cannot be protected.
     */
    fun validateClass(targetClass: Class<*>) {
        ReflectionUtils.doWithMethods(targetClass) { method ->
            if (IdempotentAnnotationResolver.find(method, targetClass) != null) {
                resolve(method, targetClass)
            }
        }
    }

    private fun createMethodMetadata(
        method: Method,
        targetClass: Class<*>,
    ): IdempotentMethodMetadata {
        val annotation = checkNotNull(IdempotentAnnotationResolver.find(method, targetClass))
        val keyParameterIndexes = findKeyParameterIndexes(method, targetClass)

        validateProxyInterception(method, targetClass)
        validateAsyncReturnType(method, targetClass)
        validateReturnType(method)
        validateKeySource(annotation, keyParameterIndexes, method)

        IdempotencyRequest.of(annotation.scope, "validation")

        val operationsBeanName = checkNotNull(beanFactory.resolveEmbeddedValue(annotation.operationsBean))
        require(
            operationsBeanName.isEmpty() || operationsBeanName.isNotBlank(),
        ) { "Once instance bean name must not be blank" }

        val operations =
            if (operationsBeanName.isEmpty()) {
                beanFactory.resolveNamedBean(IdempotencyOperations::class.java).beanInstance
            } else {
                beanFactory.getBean<IdempotencyOperations>(operationsBeanName)
            }

        validateTransaction(method, targetClass)

        return IdempotentMethodMetadata(
            method = method,
            scope = annotation.scope,
            keyExpression = annotation.key.takeIf { it.isNotBlank() }?.let(expressionParser::parseExpression),
            keyParameterIndex = keyParameterIndexes.singleOrNull(),
            operations = operations,
            retentionPolicy = retentionPolicyResolver.resolve(annotation),
            returnsVoid = returnsVoid(method),
        )
    }

    private fun validateTransaction(
        method: Method,
        targetClass: Class<*>,
    ) {
        val attributes = transactionAttributeSource.getTransactionAttribute(method, targetClass) ?: return
        require(attributes.propagationBehavior == PROPAGATION_NOT_SUPPORTED) {
            "Once owns its transaction; remove @Transactional or use NOT_SUPPORTED on $method"
        }
    }

    private fun validateProxyInterception(
        method: Method,
        targetClass: Class<*>,
    ) {
        require(Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers)) {
            "@Idempotent requires a public instance method: $method"
        }
        require(!Modifier.isFinal(method.modifiers) && !Modifier.isFinal(targetClass.modifiers)) {
            "@Idempotent requires an interceptable class and method: $method"
        }
    }

    private fun validateAsyncReturnType(
        method: Method,
        targetClass: Class<*>,
    ) {
        val asynchronousMethod = IdempotentAnnotationResolver.find(method, targetClass, Async::class.java) != null
        val asynchronousClass = AnnotatedElementUtils.hasAnnotation(targetClass, Async::class.java)

        require(!(asynchronousMethod || asynchronousClass) || returnsVoid(method)) {
            "Asynchronous @Idempotent methods must return void/Unit: $method"
        }
    }

    private fun validateReturnType(method: Method) {
        val returnsParameterizedResult =
            method.returnType == IdempotencyResult::class.java &&
                method.genericReturnType is ParameterizedType

        require(returnsVoid(method) || returnsParameterizedResult) {
            "@Idempotent methods must return void/Unit or IdempotencyResult<T>: $method"
        }
    }

    private fun returnsVoid(method: Method): Boolean =
        method.returnType == Void.TYPE || method.returnType == Unit::class.java

    private fun findKeyParameterIndexes(
        method: Method,
        targetClass: Class<*>,
    ): Set<Int> =
        IdempotentAnnotationResolver
            .declarations(method, targetClass)
            .flatMap { findAnnotatedParameterIndexes(it) }
            .toSet()

    private fun findAnnotatedParameterIndexes(method: Method): Set<Int> {
        val keyParameterIndexes = mutableSetOf<Int>()

        method.parameters.forEachIndexed { parameterIndex, parameter ->
            if (parameter.isAnnotationPresent(IdempotencyKey::class.java)) {
                keyParameterIndexes.add(parameterIndex)
            }
        }

        return keyParameterIndexes
    }

    private fun validateKeySource(
        annotation: Idempotent,
        keyParameterIndexes: Set<Int>,
        method: Method,
    ) {
        val hasExpression = annotation.key.isNotBlank()
        val hasOnlyExpression = hasExpression && keyParameterIndexes.isEmpty()
        val hasOnlyKeyParameter = !hasExpression && keyParameterIndexes.size == 1

        require(hasOnlyExpression || hasOnlyKeyParameter) {
            "@Idempotent requires exactly one key expression or @IdempotencyKey parameter: $method"
        }
    }
}
