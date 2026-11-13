package io.namastack.once.annotation

import io.namastack.once.IdempotencyResult
import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.IdempotencyResult.Executed
import io.namastack.once.retention.ForeverIdempotencyRetentionPolicy
import io.namastack.once.retention.IdempotencyRetentionPolicy
import kotlin.reflect.KClass

/**
 * Protects a public Spring bean method with a local database transaction and marker.
 *
 * Methods return `void`/Kotlin `Unit`, or the parameterized [IdempotencyResult] interface. A result
 * body returns [Executed]; the proxy supplies [Duplicate] when the body is skipped. Void methods
 * discard that same result. Calls must pass through a Spring proxy; self-invocation is unsupported.
 *
 * With Spring asynchronous method execution enabled, async adapters must return void/Unit. Once
 * starts and completes its own transaction on the worker thread. Existing transactions are rejected.
 * `@ApplicationModuleListener` can share this method with propagation set to `NOT_SUPPORTED`. Other
 * transaction annotations on the entry point must also use `NOT_SUPPORTED` or be removed.
 *
 * @property scope Stable, non-blank key namespace of at most 200 UTF-16 characters; never inferred from a method name.
 * @property key SpEL key expression, mutually exclusive with an [IdempotencyKey] parameter.
 * @property operationsBean Optional IdempotencyOperations bean name; empty selects the unambiguous or Primary bean.
 * @property retentionPolicy Defaults to unlimited retention; other policy classes are resolved as beans or instantiated by Spring.
 * @property retentionPolicyBean Named policy bean; mutually exclusive with a non-default policy class.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Idempotent(
    val scope: String,
    val key: String = "",
    val operationsBean: String = "",
    val retentionPolicy: KClass<out IdempotencyRetentionPolicy> = ForeverIdempotencyRetentionPolicy::class,
    val retentionPolicyBean: String = "",
)
