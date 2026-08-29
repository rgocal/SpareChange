package com.gocalsd.sparechange.activity

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.gocalsd.sparechange.SpareChange
import com.gocalsd.sparechange.listener.BillingEventListener
import com.gocalsd.sparechange.policy.LicensePolicy
import com.gocalsd.sparechange.utility.LicenseFeatureManager
import kotlinx.coroutines.launch

/**
 * Modernized DemoActivity showing how to use SpareChange with both
 * traditional listeners and modern Kotlin Flows.
 */
class DemoActivity : ComponentActivity(), BillingEventListener {

    private lateinit var spareChange: SpareChange
    private lateinit var featureManager: LicenseFeatureManager

    companion object {
        private const val TAG = "SpareChangeDemo"
        const val FEATURE_PRO = "pro_features"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize SpareChange (usually done in Application class)
        spareChange = SpareChange.init(
            context = this,
            consumableIds = setOf("gas_100"),
            oneTimeIds = setOf("pro_license"),
            subscriptionIds = setOf("premium_monthly")
        )

        // 2. Setup LicenseFeatureManager
        val policy = LicensePolicy { featureKey ->
            when (featureKey) {
                FEATURE_PRO -> setOf("pro_license", "premium_monthly")
                else -> emptySet()
            }
        }
        featureManager = LicenseFeatureManager(spareChange, policy)

        // 3. Register Listener (Traditional approach)
        spareChange.addListener(this)
        spareChange.startConnection()

        // 4. Observe Features (Modern Flow approach)
        observeBillingState()
    }

    private fun observeBillingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe if PRO features are unlocked (auto-updates on purchase/expiry)
                launch {
                    featureManager.observeFeature(FEATURE_PRO).collect { isUnlocked ->
                        Log.d(TAG, "Pro features unlocked: $isUnlocked")
                        // Update UI buttons or gated content here
                    }
                }

                // Observe raw ownership map for one-time products
                launch {
                    spareChange.ownedOneTimeProducts.collect { ownedMap ->
                        Log.d(TAG, "Owned one-time products: $ownedMap")
                    }
                }

                // Observe detailed subscription status
                launch {
                    spareChange.activeSubscriptions.collect { subsMap ->
                        subsMap["premium_monthly"]?.let { status ->
                            if (status.isActive && !status.isAutoRenewing) {
                                Log.d(TAG, "Subscription canceled but still active until expiry.")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Example method to trigger a purchase
     */
    private fun makeProPurchase() {
        val result = spareChange.launchPurchase(this, "pro_license")
        if (result.responseCode != com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Failed to launch purchase: ${result.debugMessage}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spareChange.removeListener(this)
    }

    // --- BillingEventListener Implementation ---

    override fun onBillingClientReady() {
        Log.d(TAG, "Billing Client Ready")
    }

    override fun onBillingClientUnavailable(billingResult: BillingResult) {
        Log.e(TAG, "Billing Unavailable: ${billingResult.debugMessage}")
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        Log.d(TAG, "Purchases Updated: ${billingResult.responseCode}")
    }

    override fun onOneTimeProductOwnershipChanged(productId: String, isOwned: Boolean) {
        Log.d(TAG, "One-time product $productId ownership: $isOwned")
    }

    override fun onSubscriptionOwnershipChanged(productId: String, isActive: Boolean) {
        Log.d(TAG, "Subscription $productId active: $isActive")
    }

    override fun onProductDetailsLoaded(productDetailsMap: Map<String, ProductDetails>) {
        Log.d(TAG, "Product details loaded for ${productDetailsMap.keys}")
    }

    override fun onProductPriceChanged(
        productId: String,
        oldPriceMicros: Long,
        oldCurrency: String,
        newPriceMicros: Long,
        newCurrency: String
    ) {
        Log.d(TAG, "Price changed for $productId: $oldPriceMicros -> $newPriceMicros")
    }

    override fun onPurchaseConsumed(productId: String, billingResult: BillingResult) {
        Log.d(TAG, "Purchase consumed: $productId")
    }

    override fun onPurchaseAcknowledged(productId: String, billingResult: BillingResult) {
        Log.d(TAG, "Purchase acknowledged: $productId")
    }
}
