package com.gocalsd.sparechange.utility

import com.gocalsd.sparechange.SpareChange
import com.gocalsd.sparechange.policy.LicensePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Modernized helper to ask "does the user have feature X?"
 * Integrates with SpareChange's reactive flows and supports both
 * one-time licenses and active subscriptions.
 *
 * @param billingManager The SpareChange instance.
 * @param licensePolicy The policy mapping features to SKUs.
 * @param strictSubscriptionRevocation If true, features granted by subscriptions
 *                                     are revoked the moment the user cancels
 *                                     (isAutoRenewing == false). If false (default),
 *                                     features remain active until the subscription
 *                                     actually expires.
 */
class LicenseFeatureManager(
    private val billingManager: SpareChange,
    private val licensePolicy: LicensePolicy,
    private val strictSubscriptionRevocation: Boolean = false
) {

    /**
     * Returns true if ANY SKU that unlocks this feature is currently owned.
     */
    fun hasFeature(featureKey: String): Boolean {
        val skus = licensePolicy.getSkusForFeature(featureKey)
        if (skus.isEmpty()) return false

        return skus.any { sku ->
            val isOneTimeOwned = billingManager.isOneTimeProductOwned(sku)
            val subStatus = billingManager.getSubscriptionStatus(sku)
            
            val isSubscriptionValid = if (strictSubscriptionRevocation) {
                subStatus?.isActive == true && subStatus.isAutoRenewing
            } else {
                subStatus?.isActive == true
            }

            isOneTimeOwned || isSubscriptionValid
        }
    }

    /**
     * Returns a Flow that emits true if the feature is unlocked.
     * This will automatically emit new values whenever ownership or
     * subscription status changes in real-time.
     */
    fun observeFeature(featureKey: String): Flow<Boolean> {
        val skus = licensePolicy.getSkusForFeature(featureKey)
        if (skus.isEmpty()) return kotlinx.coroutines.flow.flowOf(false)

        return combine(
            billingManager.ownedOneTimeProducts,
            billingManager.activeSubscriptions
        ) { ownedOneTime, activeSubs ->
            skus.any { sku ->
                val isOneTimeOwned = ownedOneTime[sku] == true
                val subStatus = activeSubs[sku]

                val isSubscriptionValid = if (strictSubscriptionRevocation) {
                    subStatus?.isActive == true && subStatus.isAutoRenewing
                } else {
                    subStatus?.isActive == true
                }

                isOneTimeOwned || isSubscriptionValid
            }
        }
    }

    /**
     * Convenience method to observe multiple features at once.
     * Emits true only if ALL features are unlocked.
     */
    fun observeAllFeatures(vararg featureKeys: String): Flow<Boolean> {
        val flows = featureKeys.map { observeFeature(it) }
        return combine(flows) { results ->
            results.all { it }
        }
    }

    /**
     * Convenience method to observe multiple features at once.
     * Emits true if ANY of the features are unlocked.
     */
    fun observeAnyFeature(vararg featureKeys: String): Flow<Boolean> {
        val flows = featureKeys.map { observeFeature(it) }
        return combine(flows) { results ->
            results.any { it }
        }
    }
}
