package io.namastack.demo

import io.namastack.once.IdempotencyKeyHasher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@SpringBootTest
@Import(ModulithOnceIntegrationTest.TestInfrastructure::class)
class ModulithOnceIntegrationTest {
    @Autowired private lateinit var orders: OrderService

    @Autowired private lateinit var events: ApplicationEventPublisher

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var completed: CompletedEventPublications

    @Autowired private lateinit var incomplete: IncompleteEventPublications

    @Autowired private lateinit var transactions: RecordingTransactionManager

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private lateinit var executor: ThreadPoolTaskExecutor

    @BeforeEach
    fun resetTransactionObservations() {
        awaitListenerCompletion()
        transactions.starts.clear()
        transactions.failure.set(null)
    }

    @Test
    fun `listener runs asynchronously and Once owns its transaction before completing the publication`() {
        val publisherThread = Thread.currentThread().id
        val event = orders.placeOrder(1200)
        awaitListenerCompletion()

        assertThat(invoiceCount(event)).isEqualTo(1)
        assertThat(markerCount(event)).isEqualTo(1)
        assertThat(publicationStatuses(event)).containsExactly("COMPLETED")
        val listenerTransactions = transactions.starts.filter { it.name == null }
        assertThat(listenerTransactions).hasSize(1)
        assertThat(listenerTransactions.single().threadId).isNotEqualTo(publisherThread)
        assertThat(transactions.starts.map { it.name }).doesNotContain(LISTENER_TRANSACTION)
    }

    @Test
    fun `publishing the same event again skips the body and completes both publication records`() {
        val event = orders.placeOrder(1200)
        awaitListenerCompletion()
        publishAgain(event)
        awaitListenerCompletion()

        assertThat(invoiceCount(event)).isEqualTo(1)
        assertThat(markerCount(event)).isEqualTo(1)
        assertThat(publicationStatuses(event)).containsExactlyInAnyOrder("COMPLETED", "COMPLETED")
        assertThat(completed.findAll().count { (it.event as? OrderPlaced)?.eventId == event.eventId }).isEqualTo(2)
    }

    @Test
    fun `producer rollback creates no publication marker or invoice`() {
        val event = OrderPlaced(UUID.randomUUID(), UUID.randomUUID(), 1200)
        publishingTransaction().executeWithoutResult { status ->
            events.publishEvent(event)
            status.setRollbackOnly()
        }
        awaitListenerCompletion()

        assertThat(publicationStatuses(event)).isEmpty()
        assertThat(markerCount(event)).isZero()
        assertThat(invoiceCount(event)).isZero()
    }

    @Test
    fun `listener failure rolls back the invoice and marker while keeping the publication retryable`() {
        val event = orders.placeOrder(-1200)
        awaitListenerCompletion()

        assertThat(invoiceCount(event)).isZero()
        assertThat(markerCount(event)).isZero()
        assertThat(publicationStatuses(event)).containsExactly("FAILED")
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_order WHERE id = ?",
                Int::class.java,
                event.orderId.toString(),
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `listener commit failure rolls back Once work and a registry resubmission can execute again`() {
        transactions.failure.set(CommitFailure.BEFORE_COMMIT)
        val event = orders.placeOrder(1200)
        awaitListenerCompletion()

        assertThat(transactions.failure.get()).isNull()
        assertThat(invoiceCount(event)).isZero()
        assertThat(markerCount(event)).isZero()
        assertThat(publicationStatuses(event)).containsExactly("FAILED")

        resubmit(event)
        assertThat(invoiceCount(event)).isEqualTo(1)
        assertThat(markerCount(event)).isEqualTo(1)
        assertThat(publicationStatuses(event)).containsExactly("COMPLETED")
    }

    @Test
    fun `a lost listener commit acknowledgement retries without repeating committed work`() {
        transactions.failure.set(CommitFailure.AFTER_COMMIT)
        val event = orders.placeOrder(1200)
        awaitListenerCompletion()

        assertThat(transactions.failure.get()).isNull()
        assertThat(invoiceCount(event)).isEqualTo(1)
        assertThat(markerCount(event)).isEqualTo(1)
        assertThat(publicationStatuses(event)).containsExactly("FAILED")

        resubmit(event)
        assertThat(invoiceCount(event)).isEqualTo(1)
        assertThat(markerCount(event)).isEqualTo(1)
        assertThat(publicationStatuses(event)).containsExactly("COMPLETED")
    }

    private fun publishAgain(event: OrderPlaced) {
        publishingTransaction().executeWithoutResult { events.publishEvent(event) }
    }

    private fun publishingTransaction(): TransactionTemplate =
        TransactionTemplate(transactions).apply {
            setName("test.publish")
        }

    private fun resubmit(event: OrderPlaced) {
        incomplete.resubmitIncompletePublications { (it.event as? OrderPlaced)?.eventId == event.eventId }
        awaitListenerCompletion()
    }

    private fun awaitListenerCompletion() {
        // The single worker processes this barrier after all previously submitted listener invocations.
        executor.submit {}.get(10, SECONDS)
    }

    private fun invoiceCount(event: OrderPlaced): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_invoice WHERE event_id = ?",
                Int::class.java,
                event.eventId.toString(),
            ),
        )

    private fun markerCount(event: OrderPlaced): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM once_marker WHERE idempotency_hash = ?",
                Int::class.java,
                IdempotencyKeyHasher().hash("billing.issue-invoice", event.eventId.toString()),
            ),
        )

    private fun publicationStatuses(event: OrderPlaced): List<String> =
        jdbc
            .queryForList(
                "SELECT STATUS FROM event_publication WHERE serialized_event LIKE ?",
                String::class.java,
                "%${event.eventId}%",
            ).map { checkNotNull(it) }

    @TestConfiguration(proxyBeanMethods = false)
    class TestInfrastructure {
        @Bean
        fun transactionManager(dataSource: DataSource): RecordingTransactionManager =
            RecordingTransactionManager(dataSource)

        @Bean
        fun applicationTaskExecutor(): ThreadPoolTaskExecutor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 1
                maxPoolSize = 1
                setThreadNamePrefix("modulith-test-")
            }
    }

    /**
     * Records real transaction starts and injects faults only at Once's unnamed commit boundary.
     *
     * BEFORE_COMMIT rolls back the connection to simulate a failed commit with no durable effects.
     * AFTER_COMMIT commits normally and then simulates an acknowledgement lost before Modulith sees success.
     */
    class RecordingTransactionManager(
        dataSource: DataSource,
    ) : JdbcTransactionManager(dataSource) {
        val starts = ConcurrentLinkedQueue<TransactionStart>()
        val failure = AtomicReference<CommitFailure?>()

        override fun doBegin(
            transaction: Any,
            definition: TransactionDefinition,
        ) {
            starts.add(TransactionStart(definition.name, Thread.currentThread().id))
            super.doBegin(transaction, definition)
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            val listenerCommit = TransactionSynchronizationManager.getCurrentTransactionName() == null
            val injectedFailure = if (listenerCommit) failure.getAndSet(null) else null
            if (injectedFailure == CommitFailure.BEFORE_COMMIT) {
                super.doRollback(status)
                throw TransactionSystemException("Simulated listener commit failure")
            }
            super.doCommit(status)
            if (injectedFailure == CommitFailure.AFTER_COMMIT) {
                throw TransactionSystemException("Simulated lost listener commit acknowledgement")
            }
        }
    }

    data class TransactionStart(
        val name: String?,
        val threadId: Long,
    )

    enum class CommitFailure { BEFORE_COMMIT, AFTER_COMMIT }

    private companion object {
        const val LISTENER_TRANSACTION = "io.namastack.demo.InvoiceListener.on"
    }
}
