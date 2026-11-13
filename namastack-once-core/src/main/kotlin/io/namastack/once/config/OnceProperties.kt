package io.namastack.once.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Common Once options independent of a concrete JDBC driver.
 *
 * @property enabled Enables Once auto-configuration; false disables the default infrastructure and annotation protection.
 * @property transactionManager Manager bean name for the default instance; empty uses Spring's unambiguous/Primary selection.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties("namastack.once")
data class OnceProperties(
    var enabled: Boolean = true,
    var transactionManager: String = "",
)
