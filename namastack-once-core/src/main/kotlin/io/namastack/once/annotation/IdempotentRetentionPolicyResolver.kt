package io.namastack.once.annotation

import io.namastack.once.retention.ForeverIdempotencyRetentionPolicy
import io.namastack.once.retention.IdempotencyRetentionPolicy
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.getBean

/**
 * Resolves the retention policy declared by an idempotent method.
 *
 * Named policies use their existing bean instance. Non-default policy classes use Spring bean
 * selection and are constructed through Spring only when no bean exists. Unresolved ambiguity
 * remains a startup error; the default policy uses the unlimited-retention singleton.
 *
 * @param beanFactory Registry used to find and construct retention-policy instances.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class IdempotentRetentionPolicyResolver(
    private val beanFactory: ConfigurableListableBeanFactory,
) {
    /**
     * Selects the named, typed, or unlimited retention policy for one validated method.
     *
     * @param annotation Retention configuration of the protected method.
     * @return Policy instance used to calculate expiry for new reservations.
     * @throws IllegalArgumentException if the policy configuration is contradictory.
     */
    fun resolve(annotation: Idempotent): IdempotencyRetentionPolicy {
        require(
            annotation.retentionPolicyBean.isBlank() ||
                annotation.retentionPolicy == ForeverIdempotencyRetentionPolicy::class,
        ) {
            "Choose retentionPolicy or retentionPolicyBean, not both"
        }

        if (annotation.retentionPolicyBean.isNotBlank()) {
            return beanFactory.getBean<IdempotencyRetentionPolicy>(annotation.retentionPolicyBean)
        }
        if (annotation.retentionPolicy == ForeverIdempotencyRetentionPolicy::class) {
            return ForeverIdempotencyRetentionPolicy
        }

        return resolvePolicyClass(annotation.retentionPolicy.java)
    }

    private fun resolvePolicyClass(policyClass: Class<out IdempotencyRetentionPolicy>): IdempotencyRetentionPolicy =
        beanFactory.getBeanProvider(policyClass).ifAvailable ?: beanFactory.createBean(policyClass)
}
