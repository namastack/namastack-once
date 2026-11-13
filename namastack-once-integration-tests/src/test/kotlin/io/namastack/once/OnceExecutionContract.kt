package io.namastack.once

import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.dao.DataAccessException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.function.Supplier

/** Shared execution and transaction checks, inherited by every supported database test. */
interface OnceExecutionContract : OnceDatabaseTestSupport {
    @Test
    fun `commits one effect and marker then skips a duplicate without replaying a value`() {
        val request = request("one")
        val first =
            once.execute<String>(request) {
                effect("one")
                IdempotencyResult.executed("new")
            }
        val duplicate = once.execute<Void?>(request) { error("Duplicate body must not run") }
        assertThat(first.fold({ it }, { "duplicate" })).isEqualTo("new")
        assertThat(duplicate.wasExecuted()).isFalse()
        assertThat(effects()).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT idempotency_hash FROM once_marker", String::class.java))
            .matches("[0-9a-f]{64}")
    }

    @Test
    fun `action failure rolls back all writes and allows a later execution`() {
        assertThatIllegalStateException().isThrownBy {
            once.execute<Void?>(request("failed")) {
                effect("failed")
                error("business failure")
            }
        }
        assertThat(effects()).isZero()
        assertThat(markers()).isZero()
        assertThat(
            once
                .execute<Void?>(request("failed")) {
                    effect("failed")
                    IdempotencyResult.executed()
                }.wasExecuted(),
        ).isTrue()
    }

    @Test
    fun `five failures leave no marker and a sixth attempt can commit`() {
        repeat(5) {
            assertThatIllegalStateException().isThrownBy {
                once.execute<Void?>(request("retry")) {
                    effect("retry")
                    error("failure")
                }
            }
        }
        assertThat(markers()).isZero()
        once.execute<Void?>(request("retry")) {
            effect("retry")
            IdempotencyResult.executed()
        }
        assertThat(effects()).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
    }

    @Test
    fun `existing transactions are rejected before work without poisoning the caller transaction`() {
        once.execute(request("duplicate")) { IdempotencyResult.executed() }
        TransactionTemplate(manager).executeWithoutResult {
            listOf("new", "duplicate").forEach { key ->
                assertThatIllegalStateException()
                    .isThrownBy {
                        once.execute<Void?>(request(key)) { error("must not run") }
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
                    once.execute(request("independent")) {
                        effect("independent")
                        IdempotencyResult.executed()
                    }
                }
            outer.setRollbackOnly()
        }
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `business services can join the transaction owned by Once`() {
        once.execute(request("services")) {
            TransactionTemplate(manager).executeWithoutResult { participant ->
                assertThat(participant.isNewTransaction).isFalse()
                effect("service")
            }
            IdempotencyResult.executed()
        }
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `a business service marking rollback-only cannot produce an executed outcome`() {
        assertThatThrownBy {
            once.execute(request("rollback-only-service")) {
                TransactionTemplate(manager).executeWithoutResult { participant ->
                    effect("service")
                    participant.setRollbackOnly()
                }
                IdempotencyResult.executed()
            }
        }.isInstanceOf(UnexpectedRollbackException::class.java)
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
    }

    @Test
    fun `retained identities are skipped across separate transactions`() {
        val finite = request("same").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))
        once.execute(finite) {
            effect("same")
            IdempotencyResult.executed()
        }
        clock.advance(Duration.ofSeconds(2))
        assertThat(once.execute<Void?>(finite) { error("must skip retained marker") }.wasExecuted()).isFalse()
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
                        once.execute<Void?>(request("outer")) {
                            effect("unfinished")
                            nestedOnce.execute<Void?>(request(key)) { error("nested action must not run") }
                        }
                    }.withMessageContaining("Once must own its transaction")
                assertThat(markers()).isZero()
                assertThat(effects()).isZero()
            }
        }
        assertThat(once.execute(request("outer")) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
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
                                once.execute<Void?>(request("late")) {
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
                once.execute<Void?>(request("readonly")) { error("must not run") }
            }
        }
        TransactionTemplate(manager).executeWithoutResult { status ->
            status.setRollbackOnly()
            assertThatIllegalStateException().isThrownBy {
                once.execute<Void?>(
                    request("rollback-only"),
                ) { error("must not run") }
            }
        }
        assertThat(markers()).isZero()
    }

    @Test
    fun `null wrapper and application supplied duplicate roll back whereas a null value is valid`() {
        @Suppress("UNCHECKED_CAST")
        val nullAction =
            Supplier<IdempotencyResult<String>?> {
                effect("null")
                null
            } as Supplier<IdempotencyResult<String>>
        assertThatIllegalStateException().isThrownBy { once.execute<String>(request("null"), nullAction) }
        assertThatIllegalStateException().isThrownBy {
            once.execute<String>(request("fake")) {
                effect("fake")
                Duplicate<String>(IdempotencyDuplicateContext("op", "fake", "hash"))
            }
        }
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
        assertThat(
            once
                .execute<String?>(request("null-value")) {
                    IdempotencyResult.executed<String?>(null)
                }.wasExecuted(),
        ).isTrue()
    }

    @Test
    fun `unrelated business constraint violations remain technical failures`() {
        effect("exists")
        assertThatThrownBy {
            once.execute<Void?>(request("constraint")) {
                effect("exists")
                IdempotencyResult.executed()
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
            uncertainOnce.execute(request("uncertain")) {
                effect("uncertain")
                IdempotencyResult.executed()
            }
        }.isInstanceOf(TransactionSystemException::class.java)
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
        assertThat(
            once.execute<Void?>(request("uncertain")) { error("must not repeat a durable effect") }.wasExecuted(),
        ).isFalse()
    }
}
