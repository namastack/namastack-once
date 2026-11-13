package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.core.convert.support.GenericConversionService
import java.util.UUID

class IdempotencyKeyNormalizerTest {
    private val converters =
        GenericConversionService().apply {
            addConverter(String::class.java, String::class.java) { it }
            addConverter(UUID::class.java, String::class.java) { it.toString() }
        }
    private val normalizer = IdempotencyKeyNormalizer(converters)

    @Test
    fun `converts supported keys without trimming and rejects unsupported types`() {
        val id = UUID.randomUUID()
        assertThat(normalizer.normalize(id)).isEqualTo(id.toString())
        assertThat(normalizer.normalize(" key ")).isEqualTo(" key ")
        assertThatIllegalArgumentException().isThrownBy { normalizer.normalize(" ") }
        assertThatIllegalArgumentException().isThrownBy { normalizer.normalize(Any()) }
    }
}
