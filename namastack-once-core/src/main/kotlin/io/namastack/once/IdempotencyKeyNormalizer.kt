package io.namastack.once

import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.support.GenericConversionService
import java.util.UUID

/**
 * Converts application keys through the configured registry of stable string converters.
 *
 * Once applies no additional trimming or case conversion. Registered converters control the
 * resulting representation, so their output must remain stable across retries and application nodes.
 *
 * @param conversionService Registry with explicit converters for supported application key types.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class IdempotencyKeyNormalizer(
    private val conversionService: ConversionService = defaultConversionService(),
) {
    companion object {
        /**
         * Creates the built-in converters used by default Core and Spring-configured instances.
         *
         * @return A fresh converter registry supporting strings, UUIDs, numbers, and enums.
         */
        @JvmStatic
        fun defaultConversionService(): GenericConversionService =
            GenericConversionService().apply {
                addConverter(String::class.java, String::class.java) { key -> key }
                addConverter(UUID::class.java, String::class.java) { key -> key.toString() }
                addConverter(Number::class.java, String::class.java) { key -> key.toString() }
                addConverter(Enum::class.java, String::class.java) { key -> key.name }
            }
    }

    /**
     * Converts an application key using its registered string representation.
     *
     * @param key Original key supplied by the request or annotated invocation.
     * @return The converter's non-null, non-blank string representation.
     * @throws org.springframework.core.convert.ConversionException if a registered converter fails.
     * @throws IllegalArgumentException if the key type has no converter or conversion produces a blank value.
     */
    fun normalize(key: Any): String {
        require(conversionService.canConvert(key.javaClass, String::class.java)) {
            "No String converter registered for idempotency key type ${key.javaClass.name}"
        }

        val normalizedKey = conversionService.convert(key, String::class.java)
        require(!normalizedKey.isNullOrBlank()) { "Idempotency key must not be null or blank" }

        return normalizedKey
    }
}
