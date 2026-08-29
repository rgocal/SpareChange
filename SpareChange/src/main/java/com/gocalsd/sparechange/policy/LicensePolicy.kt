package com.gocalsd.sparechange.policy

fun interface LicensePolicy {
    /**
     * Return the set of SKUs (one-time or subscription) that unlock this feature.
     * May be empty if the feature is not backed by Billing.
     */
    fun getSkusForFeature(featureKey: String): Set<String>

    companion object {
        val EMPTY = LicensePolicy { emptySet() }
    }
}
