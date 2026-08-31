package com.gocalsd.sparechange.activity

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * Modernized DemoActivity showing how to use SpareChange with Jetpack Compose.
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

        // 1. Initialize SpareChange
        spareChange = SpareChange.init(
            context = this,
            consumableIds = setOf("gas_100"),
            oneTimeIds = setOf("pro_license"),
            subscriptionIds = setOf("premium_monthly", "premium_yearly")
        )

        // 2. Setup LicenseFeatureManager
        val policy = LicensePolicy { featureKey ->
            when (featureKey) {
                FEATURE_PRO -> setOf("pro_license", "premium_monthly")
                else -> emptySet()
            }
        }
        featureManager = LicenseFeatureManager(spareChange, policy)

        // 3. Register Listener and Start Connection
        spareChange.addListener(this)
        spareChange.startConnection()

        // 4. Set Compose UI
        setContent {
            MaterialTheme {
                BillingDemoScreen(
                    spareChange = spareChange,
                    featureManager = featureManager,
                    onPurchaseClick = { productId ->
                        spareChange.launchPurchase(this, productId)
                    }
                )
            }
        }

        // Keep existing Flow observations for logging
        observeBillingState()
    }

    private fun observeBillingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    featureManager.observeFeature(FEATURE_PRO).collect { isUnlocked ->
                        Log.d(TAG, "Pro features unlocked: $isUnlocked")
                    }
                }
                launch {
                    spareChange.ownedOneTimeProducts.collect { ownedMap ->
                        Log.d(TAG, "Owned one-time products: $ownedMap")
                    }
                }
                launch {
                    spareChange.activeSubscriptions.collect { subsMap ->
                        Log.d(TAG, "Active subscriptions: ${subsMap.keys}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spareChange.removeListener(this)
    }

    // --- BillingEventListener Implementation ---
    override fun onBillingClientReady() { Log.d(TAG, "Billing Client Ready") }
    override fun onBillingClientUnavailable(billingResult: BillingResult) { Log.e(TAG, "Billing Unavailable: ${billingResult.debugMessage}") }
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) { Log.d(TAG, "Purchases Updated") }
    override fun onOneTimeProductOwnershipChanged(productId: String, isOwned: Boolean) { Log.d(TAG, "Ownership changed: $productId -> $isOwned") }
    override fun onSubscriptionOwnershipChanged(productId: String, isActive: Boolean) { Log.d(TAG, "Sub changed: $productId -> $isActive") }
    override fun onProductDetailsLoaded(productDetailsMap: Map<String, ProductDetails>) { Log.d(TAG, "Details loaded: ${productDetailsMap.keys}") }
    override fun onProductPriceChanged(productId: String, oldPriceMicros: Long, oldCurrency: String, newPriceMicros: Long, newCurrency: String) {}
    override fun onPurchaseConsumed(productId: String, billingResult: BillingResult) {}
    override fun onPurchaseAcknowledged(productId: String, billingResult: BillingResult) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingDemoScreen(
    spareChange: SpareChange,
    featureManager: LicenseFeatureManager,
    onPurchaseClick: (String) -> Unit
) {
    val isReady by spareChange.isReady.collectAsState()
    val isProUnlocked by featureManager.observeFeature(DemoActivity.FEATURE_PRO).collectAsState(initial = false)
    val ownedOneTime by spareChange.ownedOneTimeProducts.collectAsState()
    val activeSubscriptions by spareChange.activeSubscriptions.collectAsState()
    val productDetails by spareChange.productDetailsCache.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SpareChange Billing Demo") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        if (!isReady) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    FeatureStatusCard(
                        title = "Pro Features",
                        isUnlocked = isProUnlocked
                    )
                }

                item {
                    Text("One-Time Products", style = MaterialTheme.typography.titleLarge)
                }
                items(listOf("pro_license")) { id ->
                    ProductCard(
                        id = id,
                        isOwned = ownedOneTime[id] == true,
                        details = productDetails[id],
                        onPurchaseClick = { onPurchaseClick(id) }
                    )
                }

                item {
                    Text("Subscriptions", style = MaterialTheme.typography.titleLarge)
                }
                items(listOf("premium_monthly", "premium_yearly")) { id ->
                    val status = activeSubscriptions[id]
                    ProductCard(
                        id = id,
                        isOwned = status?.isActive == true,
                        details = productDetails[id],
                        onPurchaseClick = { onPurchaseClick(id) },
                        subtitle = if (status?.isActive == true) {
                            if (status.isAutoRenewing) "Auto-renewing" else "Active until expiry"
                        } else null
                    )
                }

                item {
                    Text("Consumables", style = MaterialTheme.typography.titleLarge)
                }
                items(listOf("gas_100")) { id ->
                    ProductCard(
                        id = id,
                        isOwned = false,
                        details = productDetails[id],
                        onPurchaseClick = { onPurchaseClick(id) },
                        buttonLabel = "Buy Gas"
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureStatusCard(title: String, isUnlocked: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnlocked) Color(0xFFE8F5E9) else Color(0xFFFBE9E7)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isUnlocked) Icons.Default.CheckCircle else Icons.Default.Lock,
                contentDescription = null,
                tint = if (isUnlocked) Color(0xFF2E7D32) else Color(0xFFD84315)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isUnlocked) "Status: Unlocked" else "Status: Locked",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun ProductCard(
    id: String,
    isOwned: Boolean,
    details: ProductDetails?,
    onPurchaseClick: () -> Unit,
    buttonLabel: String = "Purchase",
    subtitle: String? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            details?.let {
                Text(it.name, style = MaterialTheme.typography.bodyMedium)
                Text(it.description, style = MaterialTheme.typography.bodySmall)
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val price = details?.let { d ->
                    d.oneTimePurchaseOfferDetails?.formattedPrice
                        ?: d.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                } ?: "---"
                
                Text(
                    if (isOwned) "OWNED" else price,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                if (!isOwned) {
                    Button(onClick = onPurchaseClick) {
                        Text(buttonLabel)
                    }
                }
            }
        }
    }
}
