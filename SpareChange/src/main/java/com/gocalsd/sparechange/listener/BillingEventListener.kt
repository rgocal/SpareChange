package com.gocalsd.sparechange.listener

import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase

interface BillingEventListener {

    /** BillingClient is connected and ready. */
    fun onBillingClientReady()

    /** Billing is not available or an operation failed. */
    fun onBillingClientUnavailable(billingResult: BillingResult)

    /** Raw purchase events (new, pending, etc). */
    fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: List<Purchase>?
    )

    /** One-time (non-consumable INAPP) license ownership changed. */
    fun onOneTimeProductOwnershipChanged(
        productId: String,
        isOwned: Boolean
    )

    /** Subscription ownership changed (active or not). */
    fun onSubscriptionOwnershipChanged(
        productId: String,
        isActive: Boolean
    )

    /**
     * Fired when ProductDetails have been loaded/refreshed
     * and cached by BillingManager.
     */
    fun onProductDetailsLoaded(
        productDetailsMap: Map<String, ProductDetails>
    )

    /**
     * Called when the library detects that the price of a product has changed
     * compared to the last known value stored on this device.
     * Prices are in micros (1,000,000 micros = 1 unit of currency).
     */
    fun onProductPriceChanged(
        productId: String,
        oldPriceMicros: Long,
        oldCurrency: String,
        newPriceMicros: Long,
        newCurrency: String
    )

    /**
     * Called when a purchase has been successfully consumed.
     */
    fun onPurchaseConsumed(productId: String, billingResult: BillingResult)

    /**
     * Called when a purchase has been successfully acknowledged.
     */
    fun onPurchaseAcknowledged(productId: String, billingResult: BillingResult)
}
