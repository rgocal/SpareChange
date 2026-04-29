SpareChange is a lightweight Android library that makes Google Play Billing simple, safe, and developer-friendly.

It wraps the official Play Billing Library v8+ and provides:

✅ Easy setup & initialization

✅ Automatic ProductDetails loading (live Play Store prices)

✅ Detects price changes (increase/decrease)

✅ Detects ownership changes for:

    • Consumables
    • One-time purchases (non-consumables, licenses)
    • Subscriptions
    
✅ Automatically consumes or acknowledges purchases

✅ Unified simple API: launchPurchase("sku_id")

✅ Listener for billing availability, purchase updates, and SKU status

✅ Built in License Manager to help track sku purchases

Java Version Required : Version 17

Local Installatiohn : include(":SpareChange")
```
implementation 'com.github.rgocal:SpareChange:1.00'
```

Initialization
Call this once inside your Application class:

```
public class MyApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        Set<String> consumables = Set.of("coins_100");
        Set<String> oneTime = Set.of("license_pro");
        Set<String> subs = Set.of("sub_premium");

        BillingManager.init(
                this,
                consumables,
                oneTime,
                subs,
                true,   // autoAckNonConsumables
                true,   // autoAckSubscriptions
                true    // autoConsumeConsumables
        );
    }}

```
   
What the categories mean:
Type	Auto-consume?	Auto-Acknowledge?	Ownership Stored?	Typical Use
CONSUMABLE	Yes	No	No	Coins, boosts, refills
ONE_TIME	No	Yes	Yes	Premium license / upgrade
SUBSCRIPTION	No	Yes	Yes	Monthly / yearly access

Using in Activities / Fragments
You must register a listener:

```
public class BillingDemoActivity extends AppCompatActivity implements BillingEventListener {

    private BillingManager billing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        billing = BillingManager.getInstance();
        billing.addListener(this);
        billing.startConnection();  // safe to call repeatedly
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        billing.removeListener(this);
    }}
```

Launch a purchase
```
    BillingResult result = billing.launchPurchase(this, "license_pro");
    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
    Toast.makeText(this, "Cannot start purchase", Toast.LENGTH_SHORT).show();
    }
```

Checking ownership

```
boolean hasPro = billing.isOneTimeProductOwned("license_pro");
boolean subActive = billing.isSubscriptionActive("sub_premium");
```

ProductDetails (price, title, description)
Prices load automatically when BillingClient becomes ready.

```
@Override
public void onProductDetailsLoaded(@NonNull Map<String, ProductDetails> details) {
    ProductDetails pd = details.get("license_pro");
    if (pd != null && pd.getOneTimePurchaseOfferDetails() != null) {
        String price = pd.getOneTimePurchaseOfferDetails().getFormattedPrice();
        textViewPrice.setText(price);
    }
}
```
    
Price-change detection (unique feature)
The library automatically tracks SKU prices stored on the device and fires a callback when they change.

```
@Override
public void onProductPriceChanged(
    @NonNull String productId,
    long oldPriceMicros,
    @NonNull String oldCurrency,
    long newPriceMicros,
    @NonNull String newCurrency
) {
    double oldVal = oldPriceMicros / 1_000_000d;
    double newVal = newPriceMicros / 1_000_000d;

    Log.d("BILLING", productId + " price changed: " + oldVal + " → " + newVal);
}
```

Full API IMPLEMENTATION
```
public interface BillingEventListener {

    void onBillingClientReady();

    void onBillingClientUnavailable(BillingResult result);

    void onPurchasesUpdated(BillingResult result, List<Purchase> purchases);

    void onOneTimeProductOwnershipChanged(String sku, boolean owned);

    void onSubscriptionOwnershipChanged(String sku, boolean active);

    void onProductDetailsLoaded(Map<String, ProductDetails> details);

    void onProductPriceChanged(
        String sku,
        long oldPriceMicros,
        String oldCurrency,
        long newPriceMicros,
        String newCurrency
    );
}
```

Feature Licensing System (Optional)
If your app has gated features:
```
LicensePolicy policy = new LicensePolicy() {
    @Override
    public Set<String> getSkusForFeature(String key) {
        switch (key) {
            case "feature_deepsight": return Set.of("license_pro");
            case "feature_snapshot":  return Set.of("license_basic", "license_pro");
        }
        return Set.of();
    }
};

LicenseFeatureManager manager =
        new LicenseFeatureManager(BillingManager.getInstance(), policy);

boolean allowed = manager.hasFeature("feature_deepsight");
```
