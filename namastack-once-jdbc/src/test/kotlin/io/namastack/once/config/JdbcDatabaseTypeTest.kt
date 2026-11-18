package io.namastack.once.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class JdbcDatabaseTypeTest {
    @ParameterizedTest(name = "detects {0}")
    @MethodSource("supportedDatabases")
    fun `resolves supported database names regardless of case`(
        productName: String,
        expected: JdbcDatabaseType,
    ) {
        assertThat(JdbcDatabaseType.from(productName)).isEqualTo(expected)
        assertThat(JdbcDatabaseType.from(productName.lowercase())).isEqualTo(expected)
        assertThat(JdbcDatabaseType.from(productName.uppercase())).isEqualTo(expected)
    }

    @Test
    fun `rejects unsupported database names`() {
        assertThatIllegalArgumentException()
            .isThrownBy { JdbcDatabaseType.from("SQLite") }
            .withMessage("Unsupported database type: SQLite")
    }

    companion object {
        @JvmStatic
        fun supportedDatabases(): List<Arguments> =
            listOf(
                Arguments.of("PostgreSQL", JdbcDatabaseType.PostgreSQL),
                Arguments.of("MySQL", JdbcDatabaseType.MySQL),
                Arguments.of("MariaDB", JdbcDatabaseType.MariaDB),
                Arguments.of("H2", JdbcDatabaseType.H2),
                Arguments.of("Microsoft SQL Server", JdbcDatabaseType.SQLServer),
                Arguments.of("Oracle", JdbcDatabaseType.Oracle),
            )
    }
}
