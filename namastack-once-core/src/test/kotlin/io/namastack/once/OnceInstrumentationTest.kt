package io.namastack.once

import io.namastack.once.instrumentation.OnceInstrumentation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class OnceInstrumentationTest {
    @Test
    fun `composition preserves order immutable membership results and exception identity`() {
        val calls = mutableListOf<String>()

        fun instrument(name: String) =
            object : OnceInstrumentation {
                override fun <T> execute(
                    scope: String,
                    action: () -> IdempotencyResult<T>,
                ): IdempotencyResult<T> {
                    calls += "$name-start"
                    try {
                        return action()
                    } finally {
                        calls += "$name-stop"
                    }
                }

                override fun cleanup(
                    backend: String,
                    cutoff: Instant,
                    batchSize: Int,
                    action: () -> Int,
                ): Int {
                    calls += name
                    return action()
                }
            }
        val source = mutableListOf(instrument("first"), instrument("second"))
        val composite = OnceInstrumentation.compose(source)
        source.clear()
        val expected = IdempotencyResult.executed("value")
        assertThat(
            composite.execute("scope") {
                calls += "action"
                expected
            },
        ).isSameAs(expected)
        assertThat(calls).containsExactly("first-start", "second-start", "action", "second-stop", "first-stop")
        calls.clear()
        assertThat(composite.cleanup("jdbc", Instant.EPOCH, 2) { 2 }).isEqualTo(2)
        assertThat(calls).containsExactly("first", "second")
        val failure = IllegalStateException("failure")
        assertThatThrownBy { composite.execute<Void?>("scope") { throw failure } }.isSameAs(failure)
        assertThat(OnceInstrumentation.compose(emptyList())).isSameAs(OnceInstrumentation.NOOP)
    }
}
