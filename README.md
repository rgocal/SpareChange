# SpareChange 🪙

**SpareChange** is a modernized, lightweight Android library designed to make Google Play Billing simple, reactive, and robust. Built for Kotlin-first development, it handles the complexities of the Billing Library so you can focus on building features.

---

## 🚀 Key Features

*   **Kotlin-First & Reactive**: Built from the ground up with Coroutines and `StateFlow` for real-time status updates.
*   **Lifecycle Aware**: Automatically refreshes ownership and subscription status when your app returns to the foreground.
*   **Smart Subscriptions**: Detailed tracking of active vs. canceled states, allowing for "grace period" logic.
*   **Feature Mapping**: Use the `LicenseFeatureManager` to map multiple SKUs (one-time or subs) to a single app feature.
*   **Price Change Detection**: Automatically detects and notifies you of price changes in the Play Store.
*   **Auto-Management**: Hands-free handling of purchase consumption and acknowledgement.
*   **Robust Reconnection**: Built-in exponential backoff strategy for service disconnections.

---

## 📦 Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.rgocal:SpareChange:1.0.3")
}
```

---

## 🛠️ Setup

### 1. Initialize
Call `init` once, typically in your `Application` class. Define your SKUs here:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        SpareChange.init(
            context = this,
            consumableIds = setOf("coins_100", "coins_500"),
            oneTimeIds = setOf("pro_license_lifetime"),
            subscriptionIds = setOf("premium_monthly", "premium_yearly")
        )
    }
}
```

### 2. Connect
In your Activity or Fragment, start the connection:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        SpareChange.getInstance().startConnection()
    }
}
```

---

## 💡 Usage Patterns

### A. The Reactive Way (Recommended)
Observe billing states using Kotlin Flows. The UI will auto-update the moment a purchase finishes or a subscription expires.

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        // Observe a specific feature's availability
        featureManager.observeFeature("pro_filters").collect { isUnlocked ->
            filterButton.isEnabled = isUnlocked
            upgradeBanner.isVisible = !isUnlocked
        }
    }
}
```

### B. The Traditional Way (Listener)
Register a listener if you prefer standard callbacks:

```kotlin
class MyActivity : AppCompatActivity(), BillingEventListener {
    override fun onOneTimeProductOwnershipChanged(productId: String, isOwned: Boolean) {
        if (isOwned) { /* Unlock content */ }
    }
    
    // ... implement other methods
}
```

---

## 🎫 Subscription Management

SpareChange provides deep insight into subscription states via the `SubscriptionStatus` model.

| Property | Meaning |
| :--- | :--- |
| `isActive` | The subscription is valid (paid for and not expired). |
| `isAutoRenewing` | True if it will renew. False if the user has canceled. |
| `purchaseTime` | When the current period started. |

**Example: Detecting a Canceled Subscription**
```kotlin
val status = spareChange.getSubscriptionStatus("premium_monthly")
if (status?.isActive == true && !status.isAutoRenewing) {
    // User has canceled but still has time left. 
}
```

**Subscription Upgrades/Downgrades**
To change an existing subscription, use `SubscriptionUpdateParams`:
```kotlin
val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
    .setOldPurchaseToken(oldToken)
    .setSubscriptionReplacementMode(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE)
    .build()

spareChange.launchPurchase(activity, "premium_yearly", subscriptionUpdateParams = updateParams)
```

---

## 🛡️ License Feature Manager

Decouple your features from your SKUs. A feature can be unlocked by multiple different products.

```kotlin
val policy = LicensePolicy { featureKey ->
    when (featureKey) {
        "cloud_sync" -> setOf("pro_license_lifetime", "premium_monthly")
        else -> emptySet()
    }
}

val featureManager = LicenseFeatureManager(
    billingManager = SpareChange.getInstance(),
    licensePolicy = policy,
    strictSubscriptionRevocation = false // Grant access until expiry even if canceled
)
```

---

## 📋 API Reference

### Billing Categories

| Category | Auto-Consume | Auto-Ack | Description |
| :--- | :--- | :--- | :--- |
| `CONSUMABLE` | Yes | No | One-time items like coins or fuel. |
| `ONE_TIME` | No | Yes | Lifetime unlocks / Pro versions. |
| `SUBSCRIPTION` | No | Yes | Recurring billing items. |

### Event Callbacks
The `BillingEventListener` provides granular tracking:
*   `onBillingClientReady()`
*   `onProductDetailsLoaded(map)`
*   `onPurchaseConsumed(productId, result)`
*   `onPurchaseAcknowledged(productId, result)`
*   `onProductPriceChanged(...)` — *Detects if you've changed prices in the console.*

---

## 🤝 Contributing
Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
