package io.namastack.once

import org.junit.jupiter.api.Tag
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import javax.sql.DataSource

@Tag("database")
@Testcontainers
class MariaDbOnceIntegrationTest : AbstractOnceIntegrationTest() {
    override val database: String = "mariadb"

    override fun createDataSource(): DataSource =
        DriverManagerDataSource(container.jdbcUrl, container.username, container.password)

    companion object {
        @Container
        @JvmStatic
        val container = MariaDBContainer("mariadb:12")
    }
}
