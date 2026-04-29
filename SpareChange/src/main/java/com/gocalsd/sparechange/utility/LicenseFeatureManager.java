package com.gocalsd.sparechange.utility;

import androidx.annotation.NonNull;

import com.gocalsd.sparechange.SpareChange;
import com.gocalsd.sparechange.policy.LicensePolicy;

import java.util.Set;

/**
 * Thin helper to ask "does the user have feature X?"
 * based on the current owned one-time products in BillingManager.
 */
public class LicenseFeatureManager {

    private final SpareChange billingManager;
    private final LicensePolicy licensePolicy;

    public LicenseFeatureManager(
            @NonNull SpareChange billingManager,
            @NonNull LicensePolicy licensePolicy
    ) {
        this.billingManager = billingManager;
        this.licensePolicy = licensePolicy;
    }

    /**
     * Returns true if ANY SKU that unlocks this feature is owned.
     */
    public boolean hasFeature(@NonNull String featureKey) {
        Set<String> skus = licensePolicy.getSkusForFeature(featureKey);
        if (skus == null || skus.isEmpty()) return false;

        for (String sku : skus) {
            if (billingManager.isOneTimeProductOwned(sku)) {
                return true;
            }
        }
        return false;
    }
}
