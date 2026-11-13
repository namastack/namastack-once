package io.namastack.once

import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.annotation.IdempotencyKey
import io.namastack.once.annotation.Idempotent
import io.namastack.once.retention.IdempotencyRetentionContext
import io.namastack.once.retention.IdempotencyRetentionPolicy
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.time.Instant
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

@Service
class AnnotatedOrderHandler(
    private val jdbc: JdbcTemplate,
) {
    val calls = AtomicInteger()

    @Idempotent(scope = "order.void")
    fun process(
        @IdempotencyKey id: String,
    ) {
        write(id)
    }

    @Idempotent(scope = "order.value", key = "#id")
    fun create(id: String): IdempotencyResult<String> {
        write(id)
        return IdempotencyResult.executed("created-$id")
    }

    @Idempotent(scope = "order.empty")
    fun processWithResult(
        @IdempotencyKey id: String,
    ): IdempotencyResult<Void?> {
        write(id)
        return IdempotencyResult.executed()
    }

    @Idempotent(scope = "order.expires", key = "#p0", retentionPolicyBean = "twoDays")
    fun expiring(id: String) {
        write(id)
    }

    @Idempotent(scope = "order.null")
    fun nullResult(
        @IdempotencyKey id: String,
    ): IdempotencyResult<String>? {
        write(id)
        return null
    }

    @Idempotent(scope = "order.invalid")
    fun invalidResult(
        @IdempotencyKey id: String,
    ): IdempotencyResult<String> {
        write(id)
        return Duplicate(IdempotencyDuplicateContext("order.invalid", id, "hash"))
    }

    @Throws(IOException::class)
    @Idempotent(scope = "order.checked")
    fun checkedFailure(
        @IdempotencyKey id: String,
    ) {
        write(id)
        throw IOException("application failure")
    }

    @TestIdempotent
    fun composed(id: String) {
        write(id)
    }

    private fun write(id: String) {
        val sequence = calls.incrementAndGet()
        jdbc.update("INSERT INTO business_effect (id, amount) VALUES (?, ?)", "$id-$sequence", 1)
    }
}

interface OrderHandlerPort {
    @Idempotent(scope = "order.interface")
    fun handle(
        @IdempotencyKey id: String,
    )
}

@Service
class InterfaceOrderHandler(
    private val jdbc: JdbcTemplate,
) : OrderHandlerPort {
    override fun handle(id: String) {
        jdbc.update("INSERT INTO business_effect (id, amount) VALUES (?, ?)", id, 1)
    }
}

@Idempotent(scope = "order.composed", key = "#p0")
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TestIdempotent

@Service
class ClassRetentionOrderHandler(
    private val jdbc: JdbcTemplate,
) {
    @Idempotent(scope = "order.class-retention", retentionPolicy = TestRetentionPolicy::class)
    fun process(
        @IdempotencyKey id: String,
    ) {
        jdbc.update("INSERT INTO business_effect (id, amount) VALUES (?, ?)", id, 1)
    }
}

class TestRetentionPolicy : IdempotencyRetentionPolicy {
    override fun calculateExpiry(
        context: IdempotencyRetentionContext,
        createdAt: Instant,
    ): Optional<Instant> = Optional.of(createdAt.plusSeconds(60))
}

@Service
class TransactionalOrderHandler(
    private val jdbc: JdbcTemplate,
) {
    @Transactional
    @Idempotent(scope = "order.transactional")
    fun process(
        @IdempotencyKey id: String,
    ) {
        jdbc.update("INSERT INTO business_effect (id, amount) VALUES (?, ?)", id, 1)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class EarlyTransactionConfiguration

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(order = org.springframework.core.Ordered.LOWEST_PRECEDENCE)
class LateTransactionConfiguration

@Service
class InvalidReturnHandler {
    @Idempotent(scope = "invalid.return")
    fun handle(
        @IdempotencyKey id: String,
    ): String = id
}

@Service
class InvalidKeyHandler {
    @Idempotent(scope = "invalid.key", key = "#p0")
    fun handle(
        @IdempotencyKey id: String,
    ) {}
}

@Service
class ReadOnlyOrderHandler {
    @Transactional(readOnly = true)
    @Idempotent(scope = "invalid.readonly")
    fun handle(
        @IdempotencyKey id: String,
    ) {}
}

@Service
class RequiresNewOrderHandler(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
    )
    @Idempotent(scope = "order.requiresnew")
    fun handle(
        @IdempotencyKey id: String,
        fail: Boolean = false,
    ) {
        jdbc.update("INSERT INTO business_effect (id, amount) VALUES (?, ?)", id, 1)
        check(!fail) { "Business action failed" }
    }
}

@Service
class NonTransactionalOrderHandler(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(
        propagation = Propagation.NOT_SUPPORTED,
    )
    @Idempotent(scope = "order.owned")
    fun handle(
        @IdempotencyKey id: String,
        fail: Boolean = false,
    ) {
        jdbc.update("INSERT INTO business_effect (id, amount) VALUES (?, ?)", id, 1)
        check(!fail) { "Business action failed" }
    }
}

@Service
class AsyncOrderHandler {
    @Async
    @Idempotent(scope = "invalid.async")
    fun handle(
        @IdempotencyKey id: String,
    ): IdempotencyResult<String> = IdempotencyResult.executed(id)
}

@Service
class FinalOrderHandler {
    @Idempotent(scope = "invalid.final")
    final fun handle(
        @IdempotencyKey id: String,
    ) {}
}
