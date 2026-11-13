package io.namastack.once

import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class H2OnceIntegrationTest : AbstractOnceIntegrationTest() {
    override val database: String = "h2"

    override fun createDataSource(): DataSource =
        DriverManagerDataSource("jdbc:h2:mem:once_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "")

    @Test
    fun `transaction-aware datasource proxies preserve atomic writes and marker cleanup`() {
        val proxy = TransactionAwareDataSourceProxy(dataSource)
        val proxyManager = JdbcTransactionManager(proxy)
        val repository = createTestRepository(proxy, proxyManager)
        val proxyOnce = DefaultIdempotencyOperations(proxyManager, repository, IdempotencyKeyNormalizer(), clock)
        val identity =
            request("proxied").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))

        assertThatThrownBy {
            proxyOnce.execute<Void?>(identity) {
                effect("proxied")
                error("proxy action failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("proxy action failure")
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()

        proxyOnce.execute(identity) {
            effect("proxied")
            IdempotencyResult.executed()
        }
        assertThat(proxyOnce.execute<Void?>(identity) { error("duplicate must skip") }.wasExecuted()).isFalse()
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)

        clock.advance(Duration.ofSeconds(1))
        TransactionTemplate(proxyManager).executeWithoutResult { outer ->
            // Bind a proxy connection before cleanup starts its independent transaction.
            assertThat(JdbcTemplate(proxy).queryForObject("SELECT COUNT(*) FROM once_marker", Int::class.java))
                .isEqualTo(1)
            assertThat(repository.cleanup(clock.instant(), 10)).isEqualTo(1)
            outer.setRollbackOnly()
        }
        assertThat(markers()).isZero()
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `lock timeout is a technical failure and never a duplicate`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first =
                executor.submit<IdempotencyResult<Void?>> {
                    once.execute(request("locked")) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        IdempotencyResult.executed()
                    }
                }
            check(entered.await(10, TimeUnit.SECONDS))
            assertThatThrownBy {
                once.execute<Void?>(request("locked")) { error("timed-out action must not run") }
            }.isInstanceOf(DataAccessException::class.java)
            release.countDown()
            assertThat(first.get(10, TimeUnit.SECONDS).wasExecuted())
                .isTrue()
            assertThat(markers()).isEqualTo(1)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
