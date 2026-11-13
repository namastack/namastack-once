package io.namastack.once

import org.junit.jupiter.api.Tag
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import javax.sql.DataSource

@Tag("database")
@Testcontainers
class MySqlOnceIntegrationTest : AbstractOnceIntegrationTest() {
    override val database: String = "mysql"

    override fun createDataSource(): DataSource =
        DriverManagerDataSource(container.jdbcUrl, container.username, container.password)

    companion object {
        @Container
        @JvmStatic
        val container = MySQLContainer("mysql:9.2")
    }
}
