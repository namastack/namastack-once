package io.namastack.once.annotation

import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * Resolves merged method annotations in the concrete target's declaration hierarchy.
 *
 * Spring resolves composed annotations and bridge methods; declaration matching includes interfaces
 * implemented by the target even when a superclass supplies the method body.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal object IdempotentAnnotationResolver {
    /**
     * Finds the merged [Idempotent] declaration for a method on the concrete target class.
     *
     * @param method Candidate method, possibly declared on an interface or superclass.
     * @param targetClass Concrete application class supplying the declaration context.
     * @return The merged annotation, or `null` when no declaration is found.
     */
    fun find(
        method: Method,
        targetClass: Class<*>,
    ): Idempotent? = find(method, targetClass, Idempotent::class.java)

    /**
     * Finds a merged method annotation using Spring's annotation hierarchy lookup.
     *
     * Searches the most specific implementation first, then matching declarations. The fallback
     * includes interfaces implemented by the target when a superclass supplies the method body.
     *
     * @param A Type of annotation to resolve.
     * @param method Candidate method, possibly declared on an interface or superclass.
     * @param targetClass Concrete application class supplying the declaration context.
     * @param annotationType Annotation type to find, including through composed annotations.
     * @return The first merged annotation found, or `null` when no declaration matches.
     */
    fun <A : Annotation> find(
        method: Method,
        targetClass: Class<*>,
        annotationType: Class<A>,
    ): A? {
        val implementation = AopUtils.getMostSpecificMethod(method, targetClass)

        return AnnotatedElementUtils.findMergedAnnotation(implementation, annotationType)
            ?: declarations(method, targetClass).firstNotNullOfOrNull {
                AnnotatedElementUtils.findMergedAnnotation(it, annotationType)
            }
    }

    /**
     * Checks whether the target has a method with a discoverable [Idempotent] declaration.
     *
     * Includes non-public and final methods so validation can reject declarations that Spring
     * cannot intercept instead of silently leaving them unprotected.
     *
     * @param targetClass Application class whose methods and inherited declarations are inspected.
     * @return `true` when at least one method has an idempotency declaration.
     */
    fun hasAnnotatedMethods(targetClass: Class<*>): Boolean =
        ReflectionUtils.getAllDeclaredMethods(targetClass).any { find(it, targetClass) != null }

    /**
     * Collects declarations resolving to the same concrete implementation method.
     *
     * Includes superclass and target-interface declarations, even when the superclass supplying
     * the body does not implement those interfaces. Spring resolves generic signatures and bridges
     * before comparison, so unrelated overloads with the same name and parameter count are excluded.
     *
     * @param method Method whose corresponding declarations should be collected.
     * @param targetClass Concrete application class supplying the implementation and interfaces.
     * @return Distinct matching declarations, with the most specific implementation first.
     */
    fun declarations(
        method: Method,
        targetClass: Class<*>,
    ): List<Method> {
        val implementation = AopUtils.getMostSpecificMethod(method, targetClass)
        val candidates =
            ReflectionUtils.getAllDeclaredMethods(targetClass).asList() +
                ClassUtils.getAllInterfacesForClass(targetClass).flatMap { it.methods.asList() }

        return (
            listOf(implementation) +
                candidates.filter {
                    it.name == method.name &&
                        it.parameterCount == method.parameterCount &&
                        AopUtils.getMostSpecificMethod(it, targetClass) == implementation
                }
        ).distinct()
    }
}
