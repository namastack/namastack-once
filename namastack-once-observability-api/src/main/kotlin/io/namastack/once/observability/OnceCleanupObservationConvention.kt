package io.namastack.once.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention

/** Customizes names and tags for Once cleanup observations. @since 0.1.0 */
interface OnceCleanupObservationConvention : ObservationConvention<OnceCleanupObservationContext> {
    /** Accepts only contexts for this observation boundary. @return Whether the context is supported. */
    override fun supportsContext(context: Observation.Context): Boolean = context is OnceCleanupObservationContext
}
