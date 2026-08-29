package com.gocalsd.sparechange.model

/**
 * Detailed status for a subscription.
 *
 * @param productId The SKU / Product ID.
 * @param isActive True if the subscription is in the PURCHASED state (not expired/voided).
 * @param isAutoRenewing True if the subscription will renew at the end of the period.
 *                       If false, the user has canceled, but may still have time remaining.
 * @param purchaseToken The token associated with this purchase.
 * @param purchaseTime The time the product was purchased, in milliseconds since the epoch.
 */
data class SubscriptionStatus(
    val productId: String,
    val isActive: Boolean,
    val isAutoRenewing: Boolean,
    val purchaseToken: String,
    val purchaseTime: Long
)
