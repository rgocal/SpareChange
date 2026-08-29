# SpareChange

SpareChange is a modernized, lightweight Android library that makes Google Play Billing simple, safe, and developer-friendly.

It wraps the official Play Billing Library v7+ and provides:

✅ **Kotlin-First API**: Built with Coroutines and Flow for real-time updates.
✅ **Easy Setup**: Single-point initialization and global access.
✅ **Reactive States**: `StateFlow` support for ownership, subscriptions, and product details.
✅ **Lifecycle Aware**: Automatically refreshes ownership when the app returns to the foreground.
✅ **Automatic Management**: Handles consumption and acknowledgement automatically.
✅ **Price Change Detection**: Tracks local price changes for your SKUs.
✅ **Robust Reconnection**: Exponential backoff strategy for service disconnections.

## Installation

```kotlin
implementation("com.github.rgocal:SpareChange:1.0.3")
```

## Initialization

Call this once inside your `Application` class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val consumables = setOf("coins_100")
        val oneTime = setOf("license_pro")
        val subs = setOf("sub_premium")

        SpareChange.init(
            context = this,
            consumableIds = consumables,
            oneTimeIds = oneTime,
            subscriptionIds = subs,
            autoAckNonConsumables = true,
            autoAckSubscriptions = true,
            autoConsumeConsumables = true
        )
    }
}
```

## Usage

### Reactive State (Kotlin Flow)
The most modern way to observe billing state is via `StateFlow`.

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        SpareChange.getInstance().ownedOneTimeProducts.collect { ownedMap ->
            val hasPro = ownedMap["license_pro"] == true
            // Update UI
        }
    }
}
```

### Traditional Listener
You can still use the listener pattern for traditional event handling.

```kotlin
class MyActivity : AppCompatActivity(), BillingEventListener {
    private lateinit var billing: SpareChange

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billing = SpareChange.getInstance()
        billing.addListener(this)
        billing.startConnection()
    }

    override fun onDestroy() {
        super.onDestroy()
        billing.removeListener(this)
    }

    // Implement BillingEventListener methods...
}
```

### Launching a Purchase

```kotlin
val result = billing.launchPurchase(this, "license_pro")
if (result.responseCode != BillingClient.BillingResponseCode.OK) {
    Toast.makeText(this, "Cannot start purchase: ${result.debugMessage}", Toast.LENGTH_SHORT).show()
}
```

## Categories Overview

| Type | Auto-consume? | Auto-Acknowledge? | Ownership Stored? | Typical Use |
| :--- | :--- | :--- | :--- | :--- |
| **CONSUMABLE** | Yes | No | No | Coins, boosts, refills |
| **ONE_TIME** | No | Yes | Yes | Premium license / upgrade |
| **SUBSCRIPTION** | No | Yes | Yes | Monthly / yearly access |

## Advanced Features

### Price Change Detection
SpareChange tracks SKU prices locally and notifies you if they change (useful for "Price Dropped!" notifications).

```kotlin
override fun onProductPriceChanged(
    productId: String,
    oldPriceMicros: Long,
    oldCurrency: String,
    newPriceMicros: Long,
    newCurrency: String
) {
    // Notify user about price change
}
```

### Lifecycle Awareness
The library automatically calls `refreshOwnership()` whenever your app moves to the `RESUMED` state. This ensures your app catches subscription cancellations or outside purchases immediately.

### Reconnection Logic
If the Billing Service disconnects, SpareChange uses an exponential backoff strategy (up to 30s) to reconnect without flooding the system.

## API Reference

### BillingEventListener
```kotlin
interface BillingEventListener {
    fun onBillingClientReady()
    fun onBillingClientUnavailable(billingResult: BillingResult)
    fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?)
    fun onOneTimeProductOwnershipChanged(productId: String, isOwned: Boolean)
    fun onSubscriptionOwnershipChanged(productId: String, isActive: Boolean)
    fun onProductDetailsLoaded(productDetailsMap: Map<String, ProductDetails>)
    fun onProductPriceChanged(productId: String, oldPriceMicros: Long, oldCurrency: String, newPriceMicros: Long, newCurrency: String)
    fun onPurchaseConsumed(productId: String, billingResult: BillingResult)
    fun onPurchaseAcknowledged(productId: String, billingResult: BillingResult)
}
```

### License Feature Manager
A modernized helper to map SKUs to specific app features with full reactive support.

```kotlin
// Define policy
val policy = LicensePolicy { featureKey ->
    when (featureKey) {
        "premium_filters" -> setOf("license_pro", "sub_premium")
        else -> emptySet()
    }
}

// Initialize manager
// strictSubscriptionRevocation = false (default): User keeps access until sub expires.
// strictSubscriptionRevocation = true: User loses access immediately upon hitting "Cancel".
val manager = LicenseFeatureManager(
    billingManager = SpareChange.getInstance(),
    licensePolicy = policy,
    strictSubscriptionRevocation = false 
)

// Observe feature (auto-updates on purchase or cancellation)
manager.observeFeature("premium_filters").collect { unlocked ->
    // ...
}
```

## Subscription Management

SpareChange provides a detailed `SubscriptionStatus` model to help you understand exactly what's happening with a user's subscription.

```kotlin
val status = spareChange.getSubscriptionStatus("sub_premium")

status?.let {
    if (it.isActive && !it.isAutoRenewing) {
        // User has canceled, but still has time remaining!
        // You could show a "Renew now to keep access" banner.
    }
}
```

| Property | Description |
| :--- | :--- |
| `isActive` | True if the subscription is still in the `PURCHASED` state. |
| `isAutoRenewing` | False if the user has canceled the subscription in the Play Store. |
| `purchaseTime` | Epoch time when the current period started. |
