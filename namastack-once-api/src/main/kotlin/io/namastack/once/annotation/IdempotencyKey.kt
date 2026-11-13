package io.namastack.once.annotation

/**
 * Selects the parameter providing an [Idempotent] method's invocation key.
 *
 * Exactly one parameter may carry this annotation when no key expression is configured. The key
 * must be non-null and convertible by the selected Once instance's key normalizer.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class IdempotencyKey
