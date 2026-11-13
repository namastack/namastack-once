package io.namastack.once.annotation

import io.namastack.once.IdempotencyOperations
import io.namastack.once.retention.IdempotencyRetentionPolicy
import org.springframework.expression.Expression
import java.lang.reflect.Method

/**
 * Validated metadata cached per concrete method and target class instead of parsing expressions for every call.
 *
 * @property method Concrete implementation method used to resolve parameter names.
 * @property scope Stable application scope identifying the protected action.
 * @property keyExpression Parsed expression, or null when a parameter supplies the key.
 * @property keyParameterIndex Zero-based key parameter position, or null when an expression supplies it.
 * @property operations Fixed execution service selected by operationsBean or Spring's default bean resolution.
 * @property retentionPolicy Policy applied to new marker reservations.
 * @property returnsVoid Whether the adapter discards the core result on normal return.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal data class IdempotentMethodMetadata(
    val method: Method,
    val scope: String,
    val keyExpression: Expression?,
    val keyParameterIndex: Int?,
    val operations: IdempotencyOperations,
    val retentionPolicy: IdempotencyRetentionPolicy,
    val returnsVoid: Boolean,
)
