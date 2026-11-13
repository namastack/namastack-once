package io.namastack.once

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import java.util.UUID
import javax.sql.DataSource

class JpaOnceIntegrationTest : AbstractOnceIntegrationTest() {
    private lateinit var factory: LocalContainerEntityManagerFactoryBean
    override val database: String = "h2"

    override fun createDataSource(): DataSource =
        DriverManagerDataSource("jdbc:h2:mem:jpa_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")

    override fun createTransactionManager(dataSource: DataSource): PlatformTransactionManager {
        factory =
            LocalContainerEntityManagerFactoryBean().apply {
                setDataSource(dataSource)
                setPackagesToScan("io.namastack.once")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
                afterPropertiesSet()
            }
        return JpaTransactionManager(checkNotNull(factory.`object`))
    }

    @AfterEach
    fun closeEntityManagerFactory() {
        if (::factory.isInitialized) factory.destroy()
    }

    @Test
    fun `ORM writes commit with the JDBC marker and both roll back on failure`() {
        val entityManager = SharedEntityManagerCreator.createSharedEntityManager(checkNotNull(factory.`object`))
        once.execute(request("jpa-success")) {
            entityManager.persist(BusinessEffectEntity("jpa-success", 1))
            IdempotencyResult.executed()
        }
        assertThat(effects()).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
        assertThatThrownBy {
            once.execute<Void?>(request("jpa-failure")) {
                entityManager.persist(BusinessEffectEntity("jpa-failure", 1))
                entityManager.flush()
                error("application failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(effects()).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
    }

    /** Application entity used to verify ORM writes and JDBC markers share one local transaction. */
    @Entity
    @Table(name = "business_effect")
    class BusinessEffectEntity(
        @Id var id: String = "",
        var amount: Int = 0,
    )
}
