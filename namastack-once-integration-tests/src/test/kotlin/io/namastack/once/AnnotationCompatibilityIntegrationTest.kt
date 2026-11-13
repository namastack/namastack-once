package io.namastack.once

import io.namastack.once.annotation.IdempotencyKey
import io.namastack.once.annotation.Idempotent
import io.namastack.once.annotation.IdempotentMethodInterceptor
import io.namastack.once.annotation.IdempotentMethodResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.support.AutowireCandidateQualifier
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.annotation.AliasFor
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.util.UUID

class AnnotationCompatibilityIntegrationTest {
    private val dataSource =
        DriverManagerDataSource("jdbc:h2:mem:annotations_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "").also {
            ResourceDatabasePopulator(ClassPathResource("schema/h2.sql")).execute(it)
            JdbcTemplate(it).execute("CREATE TABLE business_effect (id VARCHAR(100) PRIMARY KEY)")
        }
    private val jdbc = JdbcTemplate(dataSource)
    private val beanFactory = DefaultListableBeanFactory()
    private val manager = JdbcTransactionManager(dataSource)
    private val once =
        DefaultIdempotencyOperations(
            manager,
            createTestRepository(dataSource, manager),
            IdempotencyKeyNormalizer(),
            Clock.systemUTC(),
        )
    private val resolver = IdempotentMethodResolver(beanFactory)

    init {
        beanFactory.registerSingleton("once", once)
    }

    @Test
    fun `inherited methods retain the scope of each concrete target`() {
        val first = proxy(FirstHandler(jdbc))
        val second = proxy(SecondHandler(jdbc))

        repeat(2) {
            first.handle("same-key")
            second.handle("same-key")
        }

        assertThat(jdbc.queryForList("SELECT scope FROM once_marker", String::class.java))
            .containsExactlyInAnyOrder("first", "second")
        assertThat(jdbc.queryForList("SELECT id FROM business_effect", String::class.java))
            .containsExactlyInAnyOrder("first-same-key", "second-same-key")
    }

    @Test
    fun `generic interface keys are discovered through inherited type bindings`() {
        val handler = proxy(StringHandler(jdbc))

        repeat(2) { handler.handle("generic-key") }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_effect", Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT scope FROM once_marker", String::class.java)).isEqualTo("generic")
    }

    @Test
    fun `transaction suspension accepts Spring transaction manager qualifiers`() {
        val definition = RootBeanDefinition(JdbcTransactionManager::class.java, { manager })
        definition.addQualifier(AutowireCandidateQualifier(Qualifier::class.java, "orders"))
        beanFactory.registerBeanDefinition("actualManagerName", definition)
        val selectedManager =
            BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                beanFactory,
                PlatformTransactionManager::class.java,
                "orders",
            )
        assertThat(selectedManager).isSameAs(manager)
        val handler = proxy(QualifiedHandler(jdbc), transactional = true)

        repeat(2) { handler.handle("qualified-key") }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_effect", Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM once_marker", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `overridden methods inherit superclass key annotations`() {
        val handler = proxy(InheritedKeyHandler(jdbc))
        repeat(2) { handler.handle("inherited-key") }
        assertThat(jdbc.queryForList("SELECT id FROM business_effect", String::class.java))
            .containsExactly("inherited-key")
    }

    @Test
    fun `generic superclass keys survive multiple inheritance levels and bridge methods`() {
        val handler = proxy(GenericKeyHandler(jdbc))
        repeat(2) { handler.handle("generic-base-key") }
        assertThat(jdbc.queryForList("SELECT id FROM business_effect", String::class.java))
            .containsExactly("generic-base-key")
    }

    @Test
    fun `repeating a superclass key annotation does not introduce a competing key`() {
        val handler = proxy(RepeatedKeyHandler(jdbc))
        repeat(2) { handler.handle("repeated-key") }
        assertThat(jdbc.queryForList("SELECT id FROM business_effect", String::class.java))
            .containsExactly("repeated-key")
    }

    @Test
    fun `unrelated overloads cannot supply an inherited key`() {
        assertThatThrownBy { proxy(OverloadedKeyHandler()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("exactly one key expression or @IdempotencyKey")
    }

    @Test
    fun `composed annotation aliases use Spring merged annotation semantics`() {
        val handler = proxy(AliasedHandler(jdbc))
        repeat(2) { handler.handle("aliased-key") }
        assertThat(jdbc.queryForList("SELECT scope FROM once_marker", String::class.java))
            .containsExactly("alias-scope")
        assertThat(jdbc.queryForList("SELECT id FROM business_effect", String::class.java))
            .containsExactly("aliased-key")
    }

    private fun <T : Any> proxy(
        target: T,
        transactional: Boolean = false,
    ): T {
        resolver.validateClass(target.javaClass)
        val factory = ProxyFactory(target)
        factory.isProxyTargetClass = true
        if (transactional) {
            val transactionAdvice = TransactionInterceptor()
            transactionAdvice.setBeanFactory(beanFactory)
            transactionAdvice.setTransactionAttributeSource(AnnotationTransactionAttributeSource())
            transactionAdvice.afterPropertiesSet()
            factory.addAdvice(transactionAdvice)
        }
        factory.addAdvice(IdempotentMethodInterceptor(resolver, beanFactory))

        @Suppress("UNCHECKED_CAST")
        return factory.proxy as T
    }

    interface FirstPort {
        @Idempotent(scope = "first", key = "#p0")
        fun handle(id: String)
    }

    interface SecondPort {
        @Idempotent(scope = "second", key = "#p0")
        fun handle(id: String)
    }

    open class BaseHandler(
        private val jdbc: JdbcTemplate,
        private val prefix: String,
    ) {
        open fun handle(id: String) {
            jdbc.update("INSERT INTO business_effect VALUES (?)", "$prefix-$id")
        }
    }

    open class FirstHandler(
        jdbc: JdbcTemplate,
    ) : BaseHandler(jdbc, "first"),
        FirstPort

    open class SecondHandler(
        jdbc: JdbcTemplate,
    ) : BaseHandler(jdbc, "second"),
        SecondPort

    interface GenericPort<T> {
        @Idempotent(scope = "generic")
        fun handle(
            @IdempotencyKey id: T,
        )
    }

    interface StringPort : GenericPort<String>

    open class StringHandler(
        private val jdbc: JdbcTemplate,
    ) : StringPort {
        override fun handle(id: String) {
            jdbc.update("INSERT INTO business_effect VALUES (?)", id)
        }
    }

    open class QualifiedHandler(
        private val jdbc: JdbcTemplate,
    ) {
        @Transactional(transactionManager = "orders", propagation = Propagation.NOT_SUPPORTED)
        @Idempotent(scope = "qualified", key = "#p0")
        open fun handle(id: String) {
            check(TransactionSynchronizationManager.isActualTransactionActive())
            jdbc.update("INSERT INTO business_effect VALUES (?)", id)
        }
    }

    open class KeyBase {
        @Idempotent(scope = "inherited")
        open fun handle(
            @IdempotencyKey id: String,
        ) {}
    }

    open class InheritedKeyHandler(
        private val jdbc: JdbcTemplate,
    ) : KeyBase() {
        override fun handle(id: String) {
            jdbc.update("INSERT INTO business_effect VALUES (?)", id)
        }
    }

    open class RepeatedKeyHandler(
        private val jdbc: JdbcTemplate,
    ) : KeyBase() {
        override fun handle(
            @IdempotencyKey id: String,
        ) {
            jdbc.update("INSERT INTO business_effect VALUES (?)", id)
        }
    }

    open class GenericKeyBase<T> {
        @Idempotent(scope = "generic-base")
        open fun handle(
            @IdempotencyKey id: T,
        ) {}
    }

    open class IntermediateKeyBase<T> : GenericKeyBase<T>()

    open class GenericKeyHandler(
        private val jdbc: JdbcTemplate,
    ) : IntermediateKeyBase<String>() {
        override fun handle(id: String) {
            jdbc.update("INSERT INTO business_effect VALUES (?)", id)
        }
    }

    open class OverloadBase {
        open fun handle(
            @IdempotencyKey id: Int,
        ) {}
    }

    open class OverloadedKeyHandler : OverloadBase() {
        @Idempotent(scope = "overload")
        open fun handle(id: String) {}
    }

    @Idempotent(scope = "default-scope", key = "#p0")
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class AliasedIdempotent(
        @get:AliasFor(annotation = Idempotent::class, attribute = "scope")
        val value: String = "default-scope",
    )

    open class AliasedHandler(
        private val jdbc: JdbcTemplate,
    ) {
        @AliasedIdempotent("alias-scope")
        open fun handle(id: String) {
            jdbc.update("INSERT INTO business_effect VALUES (?)", id)
        }
    }
}
