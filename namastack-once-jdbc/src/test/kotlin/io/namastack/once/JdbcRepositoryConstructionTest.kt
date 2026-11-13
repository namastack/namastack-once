package io.namastack.once

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.once.config.JdbcDatabaseType
import io.namastack.once.query.JdbcOnceMarkerQueriesFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

class JdbcRepositoryConstructionTest {
    @Test
    fun `construction opens no connection and first use detects the dialect once using the bound connection`() {
        val connection =
            mockk<Connection>(relaxed = true) {
                every { metaData.databaseProductName } returns "H2"
            }
        val dataSource = mockk<DataSource> { every { getConnection() } returns connection }
        val repository =
            JdbcIdempotencyMarkerRepository(dataSource, DataSourceTransactionManager(dataSource)) {
                JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.detect(dataSource), "once_marker")
            }
        verify(exactly = 0) { dataSource.connection }

        repeat(2) { assertThat(repository.cleanup(Instant.EPOCH, 10)).isZero() }

        verify(exactly = 1) { connection.metaData.databaseProductName }
        // One connection per cleanup transaction; detection must not borrow another pool slot.
        verify(exactly = 2) { dataSource.connection }
        verify(exactly = 2) { connection.close() }
    }
}
