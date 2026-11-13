package io.namastack.once.observability

import io.micrometer.common.KeyValues
import io.micrometer.common.docs.KeyName
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.docs.ObservationDocumentation

/** Documented observation boundaries and bounded metric dimensions. @since 0.1.0 */
enum class OnceObservationDocumentation : ObservationDocumentation {
    /** A complete protected invocation, including commit or rollback. */
    ONCE_EXECUTE {
        /** Returns the default execution convention. */
        override fun getDefaultConvention(): Class<out ObservationConvention<out Observation.Context>> =
            DefaultOnceExecuteObservationConvention::class.java

        /** Returns metric dimensions; scope is deliberately trace-only by default. */
        override fun getLowCardinalityKeyNames(): Array<out KeyName> = arrayOf(Keys.OUTCOME)

        /** Returns trace-only attributes. */
        override fun getHighCardinalityKeyNames(): Array<out KeyName> = arrayOf(Keys.SCOPE)
    },

    /** One bounded cleanup transaction, including commit. */
    ONCE_CLEANUP_BATCH {
        /** Returns the default cleanup convention. */
        override fun getDefaultConvention(): Class<out ObservationConvention<out Observation.Context>> =
            DefaultOnceCleanupObservationConvention::class.java

        /** Returns bounded cleanup metric dimensions. */
        override fun getLowCardinalityKeyNames(): Array<out KeyName> = arrayOf(Keys.BACKEND, Keys.OUTCOME)

        /** Returns trace-only batch attributes. */
        override fun getHighCardinalityKeyNames(): Array<out KeyName> = arrayOf(Keys.BATCH_SIZE, Keys.DELETED_COUNT)
    }, ;

    /** Attribute names shared by documentation and conventions. @property key Exported attribute name. */
    enum class Keys(
        private val key: String,
    ) : KeyName {
        /** Final execution or cleanup outcome. */
        OUTCOME("once.outcome"),

        /** Application-configured namespace, excluded from metrics. */
        SCOPE("once.scope"),

        /** Persistence adapter. */
        BACKEND("once.backend"),

        /** Requested batch limit, excluded from metrics. */
        BATCH_SIZE("once.cleanup.batch.size"),

        /** Committed deletion count, excluded from metrics. */
        DELETED_COUNT("once.cleanup.deleted.count"),
        ;

        /** Returns the exported attribute name. */
        override fun asString(): String = key
    }

    /** Default execution timer and span convention. */
    class DefaultOnceExecuteObservationConvention : OnceExecuteObservationConvention {
        /** Returns the stable observation name. */
        override fun getName(): String = "once.execute"

        /** Returns the span display name. */
        override fun getContextualName(context: OnceExecuteObservationContext): String = "once execute"

        /** Returns the final outcome as a bounded metric dimension. */
        override fun getLowCardinalityKeyValues(context: OnceExecuteObservationContext): KeyValues =
            KeyValues.of(Keys.OUTCOME.withValue(context.outcome))

        /** Returns the namespace for trace inspection without exposing the application key. */
        override fun getHighCardinalityKeyValues(context: OnceExecuteObservationContext): KeyValues =
            KeyValues.of(Keys.SCOPE.withValue(context.scope))
    }

    /** Default cleanup timer and span convention. */
    class DefaultOnceCleanupObservationConvention : OnceCleanupObservationConvention {
        /** Returns the stable observation name. */
        override fun getName(): String = "once.cleanup.batch"

        /** Returns the span display name. */
        override fun getContextualName(context: OnceCleanupObservationContext): String = "once cleanup batch"

        /** Returns the adapter and final outcome as bounded metric dimensions. */
        override fun getLowCardinalityKeyValues(context: OnceCleanupObservationContext): KeyValues =
            KeyValues.of(Keys.BACKEND.withValue(context.backend), Keys.OUTCOME.withValue(context.outcome))

        /** Returns batch details as trace-only attributes. */
        override fun getHighCardinalityKeyValues(context: OnceCleanupObservationContext): KeyValues =
            KeyValues.of(
                Keys.BATCH_SIZE.withValue(context.batchSize.toString()),
                Keys.DELETED_COUNT.withValue(context.deletedCount.toString()),
            )
    }
}
