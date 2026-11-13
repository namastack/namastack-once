package io.namastack.once.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class JdbcDatabaseTypeTest {
    @Test
    fun `supports the verified dialects and rejects untested databases`() {
        assertThat(JdbcDatabaseType.from("PostgreSQL")).isEqualTo(JdbcDatabaseType.POSTGRESQL)
        assertThat(JdbcDatabaseType.from("MySQL")).isEqualTo(JdbcDatabaseType.MYSQL)
        assertThat(JdbcDatabaseType.from("MariaDB")).isEqualTo(JdbcDatabaseType.MARIADB)
        assertThat(JdbcDatabaseType.from("H2")).isEqualTo(JdbcDatabaseType.H2)
        assertThat(JdbcDatabaseType.from("Microsoft SQL Server")).isEqualTo(JdbcDatabaseType.SQLSERVER)
        assertThat(JdbcDatabaseType.from("Oracle")).isEqualTo(JdbcDatabaseType.ORACLE)
        listOf("SQLite").forEach { name ->
            assertThatIllegalArgumentException().isThrownBy { JdbcDatabaseType.from(name) }
        }
    }
}
