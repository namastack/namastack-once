package io.namastack.once.config

import io.namastack.once.IdempotencyKeyNormalizer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.converter.Converter
import java.time.Clock

/**
 * Provides clocks and key conversion shared by fixed Once instances.
 *
 * Application-provided Clock and key normalizer beans replace their defaults.
 * Converter beans extend the dedicated key conversion service unless the application supplies
 * its own bean named onceConversionService.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["namastack.once.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OnceProperties::class)
class OnceCoreInfrastructureAutoConfiguration {
    /**
     * Creates the default UTC clock for marker reservation and cleanup timestamps.
     *
     * @return The system UTC clock when the application has not supplied its own Clock bean.
     */
    @Bean
    @ConditionalOnMissingBean
    fun onceClock(): Clock = Clock.systemUTC()

    /**
     * Creates the dedicated converter registry used to form stable idempotency keys.
     *
     * Application converters are registered after the supported built-in key types. Arbitrary
     * objects are not accepted through a catch-all toString converter.
     *
     * @param converters Application converter beans in their Spring-defined order.
     * @return The Once conversion service when no bean named onceConversionService exists.
     */
    @Bean("onceConversionService")
    @ConditionalOnMissingBean(name = ["onceConversionService"])
    fun onceConversionService(converters: ObjectProvider<Converter<*, *>>): ConversionService {
        val conversionService = IdempotencyKeyNormalizer.defaultConversionService()
        converters.orderedStream().forEach { converter -> conversionService.addConverter(converter) }

        return conversionService
    }

    /**
     * Creates the key normalizer using the dedicated Once converter registry.
     *
     * @param conversionService Once-specific converters, isolated from general application conversion.
     * @return The default normalizer when the application has not supplied one.
     */
    @Bean
    @ConditionalOnMissingBean
    fun onceKeyNormalizer(
        @Qualifier("onceConversionService") conversionService: ConversionService,
    ): IdempotencyKeyNormalizer = IdempotencyKeyNormalizer(conversionService)
}
