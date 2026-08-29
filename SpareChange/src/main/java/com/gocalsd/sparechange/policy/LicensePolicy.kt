package com.gocalsd.sparechange.policy;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.Set;

public interface LicensePolicy {

    /**
     * Return the set of SKUs that unlock this feature.
     * May be empty if the feature is not backed by Billing.
     */
    @NonNull
    Set<String> getSkusForFeature(@NonNull String featureKey);

    LicensePolicy EMPTY = featureKey -> Collections.emptySet();
}
