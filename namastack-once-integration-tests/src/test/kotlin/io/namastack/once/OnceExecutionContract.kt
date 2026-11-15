package io.namastack.once

import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/** Shared execution and transaction checks, inherited by every supported database test. */
interface OnceExecutionContract : OnceDatabaseTestSupport {
    @Test
    fun `commits one effect and marker then skips a duplicate without replaying a value`() {
        val request = request("one")
        val first =
            once.execute<String>(request) {
                effect("one")
                "new"
            }
        val duplicate = once.executeWithoutResult(request) { error("Duplicate body must not run") }
        assertThat(first.fold({ it }, { "duplicate" })).isEqualTo("new")
        assertThat(duplicate.wasExecuted()).isFalse()
        assertThat(effects()).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
        assertThat(jdbc.queryForObject<String>("SELECT idempotency_hash FROM once_marker"))
            .matches("[0-9a-f]{64}")
    }

    @Test
    fun `action failure rolls back all writes and allows a later execution`() {
        assertThatIllegalStateException().isThrownBy {
            once.executeWithoutResult(request("failed")) {
                effect("failed")
                error("business failure")
            }
        }
        assertThat(effects()).isZero()
        assertThat(markers()).isZero()
        assertThat(
            once
                .executeWithoutResult(request("failed")) {
                    effect("failed")
                }.wasExecuted(),
        ).isTrue()
    }

    @Test
    fun `five failures leave no marker and a sixth attempt can commit`() {
        repeat(5) {
            assertThatIllegalStateException().isThrownBy {
                once.executeWithoutResult(request("retry")) {
                    effect("retry")
                    error("failure")
                }
            }
        }
        assertThat(markers()).isZero()
        once.executeWithoutResult(request("retry")) {
            effect("retry")
        }
        assertThat(effects()).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
    }

    @Test
    fun `existing transactions are rejected before work without poisoning the caller transaction`() {
        once.executeWithoutResult(request("duplicate")) { }
        TransactionTemplate(manager).executeWithoutResult {
            listOf("new", "duplicate").forEach { key ->
                assertThatIllegalStateException()
                    .isThrownBy {
                        once.executeWithoutResult(request(key)) { error("must not run") }
                    }.withMessageContaining("Once must own its transaction")
            }
            effect("caller-only")
        }
        assertThat(effects()).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
    }

    @Test
    fun `explicit suspension lets Once commit independently of the caller rollback`() {
        TransactionTemplate(manager).executeWithoutResult { outer ->
            effect("outer")
            TransactionTemplate(manager)
                .apply { propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED }
                .executeWithoutResult {
                    once.executeWithoutResult(request("independent")) {
                        effect("independent")
                    }
                }
            outer.setRollbackOnly()
        }
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `business services can join the transaction owned by Once`() {
        once.executeWithoutResult(request("services")) {
            TransactionTemplate(manager).executeWithoutResult { participant ->
                assertThat(participant.isNewTransaction).isFalse()
                effect("service")
            }
        }
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `a business service marking rollback-only cannot produce an executed outcome`() {
        assertThatThrownBy {
            once.executeWithoutResult(request("rollback-only-service")) {
                TransactionTemplate(manager).executeWithoutResult { participant ->
                    effect("service")
                    participant.setRollbackOnly()
                }
            }
        }.isInstanceOf(UnexpectedRollbackException::class.java)
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
    }

    @Test
    fun `retained identities are skipped across separate transactions`() {
        val finite = request("same").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))
        once.executeWithoutResult(finite) {
            effect("same")
        }
        clock.advance(Duration.ofSeconds(2))
        assertThat(once.executeWithoutResult(finite) { error("must skip retained marker") }.wasExecuted()).isFalse()
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `nested calls reject both repeated and different identities across Once instances`() {
        val otherOnce =
            DefaultIdempotencyOperations(
                manager,
                markerRepository,
                IdempotencyKeyNormalizer(),
                clock,
            )
        listOf(once, otherOnce).forEach { nestedOnce ->
            listOf("outer", "different").forEach { key ->
                assertThatIllegalStateException()
                    .isThrownBy {
                        once.executeWithoutResult(request("outer")) {
                            effect("unfinished")
                            nestedOnce.executeWithoutResult(request(key)) { error("nested action must not run") }
                        }
                    }.withMessageContaining("Once must own its transaction")
                assertThat(markers()).isZero()
                assertThat(effects()).isZero()
            }
        }
        assertThat(once.executeWithoutResult(request("outer")) { }.wasExecuted()).isTrue()
    }

    @Test
    fun `completion callbacks reject new work before any marker or business write`() {
        listOf(false, true).forEach { rollback ->
            val failures = mutableListOf<Throwable>()
            var calls = 0
            TransactionTemplate(manager).executeWithoutResult { status ->
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        private fun attempt() {
                            try {
                                once.executeWithoutResult(request("late")) {
                                    calls++
                                    effect("late")
                                    error("a failed action must never become durable")
                                }
                            } catch (failure: Throwable) {
                                failures.add(failure)
                            }
                        }

                        override fun afterCommit() = attempt()

                        override fun afterCompletion(status: Int) = attempt()
                    },
                )
                if (rollback) status.setRollbackOnly()
            }
            assertThat(failures).hasSize(if (rollback) 1 else 2)
            failures.forEach { failure ->
                assertThat(failure)
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Once must own its transaction")
            }
            assertThat(calls).isZero()
            assertThat(markers()).isZero()
            assertThat(effects()).isZero()
        }
    }

    @Test
    fun `read-only and rollback-only transactions reject execution`() {
        assertThatIllegalStateException().isThrownBy {
            TransactionTemplate(manager).apply { isReadOnly = true }.executeWithoutResult {
                once.executeWithoutResult(request("readonly")) { error("must not run") }
            }
        }
        TransactionTemplate(manager).executeWithoutResult { status ->
            status.setRollbackOnly()
            assertThatIllegalStateException().isThrownBy {
                once.executeWithoutResult(
                    request("rollback-only"),
                ) { error("must not run") }
            }
        }
        assertThat(markers()).isZero()
    }

    @Test
    fun `null business values commit and remain distinct from duplicates`() {
        val identity = request("null-value")
        val result =
            once.execute<String?>(identity) {
                effect("null-value")
                null
            }
        assertThat(result).isEqualTo(IdempotencyResult.Executed<String?>(null))
        assertThat(once.execute<String?>(identity) { error("must skip") }).isInstanceOf(Duplicate::class.java)
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `result objects returned as business values are wrapped without special treatment`() {
        val value = Duplicate<String>(IdempotencyDuplicateContext("op", "value", "hash"))
        val result = once.execute(request("result-value")) { value }
        assertThat(result).isEqualTo(IdempotencyResult.Executed(value))
        assertThat(markers()).isEqualTo(1)
    }

    @Test
    fun `no-value actions commit once and expose duplicate context`() {
        val identity = request("no-value")
        val first = once.executeWithoutResult(identity) { effect("no-value") }
        val duplicate = once.executeWithoutResult(identity) { error("must skip") }
        assertThat(first).isEqualTo(IdempotencyResult.Executed<Void?>(null))
        assertThat(duplicate.fold({ "executed" }, { it.scope })).isEqualTo(identity.scope)
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `unrelated business constraint violations remain technical failures`() {
        effect("exists")
        assertThatThrownBy {
            once.executeWithoutResult(request("constraint")) {
                effect("exists")
            }
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(markers()).isZero()
    }

    @Test
    fun `a commit failure never deletes a marker that may already be durable`() {
        val uncertain =
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?) = manager.getTransaction(definition)

                override fun commit(status: TransactionStatus) {
                    manager.commit(status)
                    throw TransactionSystemException("Simulated lost commit acknowledgment")
                }

                override fun rollback(status: TransactionStatus) = manager.rollback(status)
            }
        val uncertainOnce =
            DefaultIdempotencyOperations(
                transactionManager = uncertain,
                repository = markerRepository,
                keyNormalizer = IdempotencyKeyNormalizer(DefaultConversionService()),
                clock = clock,
            )
        assertThatThrownBy {
            uncertainOnce.executeWithoutResult(request("uncertain")) {
                effect("uncertain")
            }
        }.isInstanceOf(TransactionSystemException::class.java)
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
        assertThat(
            once.executeWithoutResult(request("uncertain")) { error("must not repeat a durable effect") }.wasExecuted(),
        ).isFalse()
    }
}
