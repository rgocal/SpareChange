package com.gocalsd.sparechange.listener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;

import java.util.List;
import java.util.Map;

public interface BillingEventListener {

    /** BillingClient is connected and ready. */
    void onBillingClientReady();

    /** Billing is not available or an operation failed. */
    void onBillingClientUnavailable(@NonNull BillingResult billingResult);

    /** Raw purchase events (new, pending, etc). */
    void onPurchasesUpdated(
            @NonNull BillingResult billingResult,
            @Nullable List<Purchase> purchases
    );

    /** One-time (non-consumable INAPP) license ownership changed. */
    void onOneTimeProductOwnershipChanged(
            @NonNull String productId,
            boolean isOwned
    );

    /** Subscription ownership changed (active or not). */
    void onSubscriptionOwnershipChanged(
            @NonNull String productId,
            boolean isActive
    );

    /**
     * Fired when ProductDetails have been loaded/refreshed
     * and cached by BillingManager.
     */
    void onProductDetailsLoaded(
            @NonNull Map<String, ProductDetails> productDetailsMap
    );

    /**
     * Called when the library detects that the price of a product has changed
     * compared to the last known value stored on this device.
     * Prices are in micros (1,000,000 micros = 1 unit of currency).
     */
    void onProductPriceChanged(
            @NonNull String productId,
            long oldPriceMicros,
            @NonNull String oldCurrency,
            long newPriceMicros,
            @NonNull String newCurrency
    );
}
