package io.namastack.once

import io.namastack.once.instrumentation.OnceInstrumentation
import org.bson.Document
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Reserves identities and cleans up markers in native MongoDB transactions.
 *
 * The identity digest is the document's unique `_id`. Marker and business writes must use the same
 * MongoDatabaseFactory and transaction. Timestamps are BSON Int64 epoch microseconds. Application
 * migrations own collection provisioning and indexes; the repository runs no initialization scripts.
 *
 * @param mongoTemplate Template sharing its database factory with the business writes.
 * @param transactionManager Native MongoDB manager used for independent cleanup transactions.
 * @param instrumentation Observes independent cleanup batches through commit.
 * @param collectionName Trusted name of the marker collection provisioned by the application.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class MongoIdempotencyMarkerRepository
    @JvmOverloads
    constructor(
        private val mongoTemplate: MongoTemplate,
        transactionManager: MongoTransactionManager,
        private val collectionName: String,
        private val instrumentation: OnceInstrumentation = OnceInstrumentation.NOOP,
    ) : IdempotencyMarkerRepository {
        /** Canonical database factory bound by Spring's native MongoDB transaction manager. */
        override val transactionResourceKey: Any =
            TransactionSynchronizationUtils.unwrapResourceIfNecessary(mongoTemplate.mongoDatabaseFactory)

        private val cleanupTransaction =
            TransactionTemplate(transactionManager).apply { propagationBehavior = PROPAGATION_REQUIRES_NEW }

        /**
         * Inserts a marker in the transaction owned by Once.
         *
         * A duplicate key requires immediate rollback. Write conflicts and other technical failures
         * propagate; an uncommitted competing reservation is not reported as a confirmed duplicate.
         *
         * @param marker Identity and retention window persisted with the business changes.
         * @return True for an inserted identity, false for a confirmed uniqueness conflict.
         * @throws IllegalStateException if no matching transaction is active.
         * @throws ArithmeticException if a timestamp exceeds the epoch microsecond storage range.
         * @throws org.springframework.dao.DataAccessException if reservation fails for another reason.
         */
        override fun reserve(marker: IdempotencyMarker): Boolean {
            check(TransactionSynchronizationManager.isActualTransactionActive()) {
                "Once reservation requires an active transaction"
            }
            validateResourceBinding()

            val document =
                Document("_id", marker.keyHash)
                    .append("scope", marker.scope)
                    .append("createdAtMicros", marker.createdAt.toEpochMicroseconds())
                    .append("expiresAtMicros", marker.expiresAt?.toEpochMicroseconds())

            return try {
                mongoTemplate.insert(document, collectionName)
                true
            } catch (_: DuplicateKeyException) {
                false
            }
        }

        /**
         * Deletes one bounded batch and returns its count after an independent transaction commits.
         *
         * MongoDB has no bounded multi-document DELETE. Selection and deletion share one transaction;
         * concurrent changes cause write conflicts instead of deleting a newly inserted replacement.
         * Markers without an expiry are excluded. Cleanup and commit failures propagate unchanged.
         *
         * @param cutoff Latest expiry eligible for deletion, inclusive.
         * @param batchSize Maximum number of markers deleted by this call.
         * @return The committed deletion count, or zero when no eligible markers remain.
         * @throws IllegalArgumentException if the batch size is not positive.
         * @throws ArithmeticException if the cutoff exceeds the epoch microsecond storage range.
         * @throws IllegalStateException if the manager does not bind an independent transaction to the template's factory.
         */
        override fun cleanup(
            cutoff: Instant,
            batchSize: Int,
        ): Int =
            instrumentation.cleanup("mongodb", cutoff, batchSize) {
                require(batchSize > 0) { "Cleanup batch size must be positive" }
                val enclosingResource = TransactionSynchronizationManager.getResource(transactionResourceKey)
                cleanupTransaction.execute {
                    validateResourceBinding()
                    check(TransactionSynchronizationManager.getResource(transactionResourceKey) !== enclosingResource) {
                        "Once cleanup requires an independent transaction bound to the configured resource"
                    }
                    deleteExpiredBatch(cutoff.toEpochMicroseconds(), batchSize)
                }
            }

        private fun deleteExpiredBatch(
            cutoffMicros: Long,
            batchSize: Int,
        ): Int {
            val candidates =
                Query(Criteria.where("expiresAtMicros").ne(null).lte(cutoffMicros))
                    .with(Sort.by(Sort.Direction.ASC, "expiresAtMicros", "_id"))
                    .limit(batchSize)
            candidates.fields().include("_id")
            val identities =
                mongoTemplate
                    .find(
                        candidates,
                        Document::class.java,
                        collectionName,
                    ).map { it.getString("_id") }
            if (identities.isEmpty()) return 0

            val deletion =
                Query(
                    Criteria
                        .where("_id")
                        .`in`(identities)
                        .and("expiresAtMicros")
                        .ne(null)
                        .lte(cutoffMicros),
                )
            return mongoTemplate.remove(deletion, collectionName).deletedCount.toInt()
        }

        private fun validateResourceBinding() {
            check(TransactionSynchronizationManager.hasResource(transactionResourceKey)) {
                "Once persistence requires the matching Spring-bound MongoDB transaction"
            }
        }
    }
