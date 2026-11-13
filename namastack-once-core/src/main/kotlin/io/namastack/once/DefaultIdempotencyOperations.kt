package io.namastack.once

import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.IdempotencyResult.Executed
import io.namastack.once.instrumentation.OnceInstrumentation
import io.namastack.once.retention.IdempotencyRetentionContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.function.Supplier

/**
 * Executes application actions and reserves their identities in the same local transaction.
 *
 * Programmatic calls and annotated methods share this execution lifecycle. A successful marker
 * reservation permits the action to run; an existing marker produces a duplicate result. Action
 * failures roll back the transaction before propagating to the caller. Existing transactions are rejected.
 *
 * The service never retries application actions or removes markers after an uncertain commit.
 * Business writes must participate in the selected transaction; remote effects are outside it.
 * Auto-configuration or application configuration supplies the key conversion and clock dependencies.
 *
 * @param transactionManager Local transaction manager matching the repository.
 * @param repository Fixed marker repository used by this instance.
 * @param keyNormalizer Converts application keys into stable string representations.
 * @param instrumentation Observes the full execution through transaction completion.
 * @param clock Supplies the reservation time used to calculate cleanup eligibility.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class DefaultIdempotencyOperations
    @JvmOverloads
    constructor(
        transactionManager: PlatformTransactionManager,
        internal val repository: IdempotencyMarkerRepository,
        private val keyNormalizer: IdempotencyKeyNormalizer,
        private val clock: Clock,
        private val instrumentation: OnceInstrumentation = OnceInstrumentation.NOOP,
    ) : IdempotencyOperations {
        private val transactionTemplate = TransactionTemplate(transactionManager)
        private val keyHasher = IdempotencyKeyHasher()

        /**
         * Executes an action after reserving its identity, or skips an already handled identity.
         *
         * Owns a new transaction and returns only after it completes. Existing transactions, including
         * nested Once calls and transaction completion callbacks, are rejected before reservation.
         *
         * @param T Type of the newly computed business value.
         * @param request Identity and retention policy.
         * @param action Synchronous application work returning a non-null [Executed] result.
         * @return The action's result, or [Duplicate] without invoking the action.
         * @throws IllegalArgumentException if key conversion or marker retention is invalid.
         * @throws IllegalStateException if a transaction is already active, ownership checks fail, or the action result is invalid.
         */
        override fun <T> execute(
            request: IdempotencyRequest,
            action: Supplier<IdempotencyResult<T>>,
        ): IdempotencyResult<T> =
            instrumentation.execute(request.scope) {
                check(!TransactionSynchronizationManager.isActualTransactionActive()) {
                    "Once must own its transaction; invoke it outside a transaction or use NOT_SUPPORTED"
                }

                val normalizedKey = keyNormalizer.normalize(request.key)
                val keyHash = keyHasher.hash(request.scope, normalizedKey)

                executeInTransaction(request, keyHash, action)
            }

        private fun <T> executeInTransaction(
            request: IdempotencyRequest,
            keyHash: String,
            action: Supplier<IdempotencyResult<T>>,
        ): IdempotencyResult<T> {
            try {
                return transactionTemplate.execute { status ->
                    validateOwnedTransaction(status)
                    reserveAndExecute(request, keyHash, status, action)
                }
            } catch (failure: IdempotencyActionFailure) {
                throw failure.original
            }
        }

        private fun <T> reserveAndExecute(
            request: IdempotencyRequest,
            keyHash: String,
            status: TransactionStatus,
            action: Supplier<IdempotencyResult<T>>,
        ): IdempotencyResult<T> {
            try {
                val marker = createMarker(request, keyHash)
                if (!repository.reserve(marker)) {
                    status.setRollbackOnly()
                    val context = IdempotencyDuplicateContext(request.scope, request.key, keyHash)

                    return Duplicate(context)
                }

                return executeAction(action)
            } catch (failure: Throwable) {
                if (failure is RuntimeException || failure is Error) throw failure
                throw IdempotencyActionFailure(failure)
            }
        }

        private fun <T> executeAction(action: Supplier<IdempotencyResult<T>>): Executed<T> {
            // Java suppliers can return null despite their declared result type.
            val result: IdempotencyResult<T>? = action.get()
            check(result is Executed<T>) { "Idempotency actions must return a non-null Executed result" }

            return result
        }

        private fun createMarker(
            request: IdempotencyRequest,
            keyHash: String,
        ): IdempotencyMarker {
            val createdAt = clock.instant().toMarkerPrecision()
            val expiresAt =
                request.retentionPolicy
                    .calculateExpiry(IdempotencyRetentionContext(request.scope), createdAt)
                    .orElse(null)
                    ?.toMarkerPrecision()

            require(expiresAt == null || expiresAt.isAfter(createdAt)) {
                "Marker expiry must be after reservation time at microsecond precision"
            }

            return IdempotencyMarker(
                keyHash = keyHash,
                scope = request.scope,
                createdAt = createdAt,
                expiresAt = expiresAt,
            )
        }

        private fun validateOwnedTransaction(status: TransactionStatus) {
            check(status.isNewTransaction) { "Once must own a new transaction; joining transactions is unsupported" }
            check(!status.isRollbackOnly && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                "Once requires a writable transaction that can commit"
            }
            check(
                TransactionSynchronizationManager.isActualTransactionActive() &&
                    TransactionSynchronizationManager.hasResource(repository.transactionResourceKey),
            ) { "The selected transaction manager did not bind the expected database resource" }
        }
    }
