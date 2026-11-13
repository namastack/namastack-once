package io.namastack.once

import org.junit.jupiter.api.Tag
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mssqlserver.MSSQLServerContainer
import javax.sql.DataSource

@Tag("database")
@Testcontainers
class SqlServerOnceIntegrationTest : AbstractOnceIntegrationTest() {
    override val database: String = "sqlserver"

    override fun createDataSource(): DataSource =
        DriverManagerDataSource(container.jdbcUrl, container.username, container.password)

    companion object {
        @Container
        @JvmStatic
        val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04").acceptLicense()
    }
}
