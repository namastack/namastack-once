package io.namastack.once

import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import javax.sql.DataSource

@Tag("database")
@Testcontainers
class OracleOnceIntegrationTest : AbstractOnceIntegrationTest() {
    override val database: String = "oracle"

    override fun createDataSource(): DataSource = pool

    companion object {
        // Reuse physical connections: rapid connection churn can exhaust Oracle Free's process limit.
        private val pool by lazy {
            HikariDataSource().apply {
                jdbcUrl = "jdbc:oracle:thin:@${container.host}:${container.getMappedPort(1521)}/FREEPDB1"
                username = "once_test"
                password = "once_test_password"
                maximumPoolSize = 4
                minimumIdle = 0
            }
        }

        @AfterAll
        @JvmStatic
        fun closePool() = pool.close()

        @Container
        @JvmStatic
        val container =
            GenericContainer("ghcr.io/gvenzl/oracle-free:23-slim-faststart")
                .withExposedPorts(1521)
                .withEnv("ORACLE_PASSWORD", "oracle_test_password")
                .withEnv("APP_USER", "once_test")
                .withEnv("APP_USER_PASSWORD", "once_test_password")
                .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*", 1))
                .withStartupTimeout(Duration.ofMinutes(5))
    }
}
