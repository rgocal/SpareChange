package com.gocalsd.sparechange;

import java.util.HashSet;
import java.util.Set;

public class MyApp extends android.app.Application {

    /// Replace sku ids with your own from the Google play console

    public static final String SKU_ONE = "001";
    public static final String SKU_TWO = "002";
    public static final String SKU_SUB = "005";
    public static final String SKU_CONSUME = "010";

    public Set<String> getLicenseSkus(){
        Set<String> skus = new HashSet<>();
        skus.add(SKU_ONE);
        skus.add(SKU_TWO);
        return skus;
    }

    public Set<String> getConsumableSkus(){
        Set<String> skus = new HashSet<>();
        skus.add(SKU_CONSUME);
        return skus;
    }

    public Set<String> getSubs(){
        Set<String> skus = new HashSet<>();
        skus.add(SKU_SUB);
        return skus;
    }

    private static MyApp INSTANCE;

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;

        BillingManager.init(
                this,
                getConsumableSkus(),
                getLicenseSkus(),
                getSubs(),
                true,   // autoAckNonConsumables
                true,   // autoAckSubscriptions
                true    // autoConsumeConsumables
        );
    }

    public static MyApp get() { return INSTANCE; }

}
