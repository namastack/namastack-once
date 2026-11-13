package io.namastack.once.observability

import io.micrometer.observation.Observation

/**
 * One invocation including transaction completion; contains no application key or digest.
 * @property scope Application-configured identity namespace.
 * @property outcome Final result: executed, duplicate, error, or unknown before completion.
 * @since 0.1.0
 */
class OnceExecuteObservationContext(
    val scope: String,
) : Observation.Context() {
    var outcome: String = "unknown"
}
