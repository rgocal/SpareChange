package com.gocalsd.sparechange;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.AcknowledgePurchaseResponseListener;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.gocalsd.sparechange.listener.BillingEventListener;
import com.gocalsd.sparechange.model.PriceInfo;
import com.gocalsd.sparechange.model.ProductCategory;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class BillingManager implements
        com.android.billingclient.api.PurchasesUpdatedListener,
        BillingClientStateListener {

    private static final String PREFS_NAME = "billinghelper_prices";
    private static final String KEY_PRICE_MICROS_PREFIX = "price_micros_";
    private static final String KEY_PRICE_CURRENCY_PREFIX = "price_currency_";

    private static volatile BillingManager INSTANCE;

    private final Context appContext;

    // SKU classification
    private final Set<String> consumableProductIds;
    private final Set<String> oneTimeProductIds;
    private final Set<String> subscriptionProductIds;

    private final List<BillingEventListener> listeners = new CopyOnWriteArrayList<>();

    // Ownership tracking
    private final Map<String, Boolean> ownedOneTimeProducts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activeSubscriptions = new ConcurrentHashMap<>();

    // ProductDetails cache for ALL SKUs
    private final Map<String, ProductDetails> productDetailsCache = new ConcurrentHashMap<>();

    private BillingClient billingClient;
    private boolean isReady = false;

    // Behavior flags
    private final boolean autoAcknowledgeNonConsumables;
    private final boolean autoAcknowledgeSubscriptions;
    private final boolean autoConsumeConsumables;

    // Price storage
    private final SharedPreferences pricePrefs;

    /**
     * Initialize the global BillingManager.
     *
     * @param context app context
     * @param consumableIds INAPP SKUs that should be consumed when purchased
     * @param oneTimeIds INAPP SKUs that are non-consumable (licenses)
     * @param subscriptionIds SUBS SKUs
     * @param autoAckNonConsumables auto-ack one-time purchases
     * @param autoAckSubscriptions auto-ack subscription purchases
     * @param autoConsumeConsumables auto-consume consumable INAPP purchases
     */
    public static BillingManager init(
            @NonNull Context context,
            @NonNull Set<String> consumableIds,
            @NonNull Set<String> oneTimeIds,
            @NonNull Set<String> subscriptionIds,
            boolean autoAckNonConsumables,
            boolean autoAckSubscriptions,
            boolean autoConsumeConsumables
    ) {
        if (INSTANCE == null) {
            synchronized (BillingManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new BillingManager(
                            context.getApplicationContext(),
                            consumableIds,
                            oneTimeIds,
                            subscriptionIds,
                            autoAckNonConsumables,
                            autoAckSubscriptions,
                            autoConsumeConsumables
                    );
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Convenience init with typical defaults:
     * - auto-ack non-consumables & subs
     * - auto-consume consumables
     */
    public static BillingManager init(
            @NonNull Context context,
            @NonNull Set<String> consumableIds,
            @NonNull Set<String> oneTimeIds,
            @NonNull Set<String> subscriptionIds
    ) {
        return init(context, consumableIds, oneTimeIds, subscriptionIds,
                true, true, true);
    }

    public static BillingManager getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("BillingManager.init() must be called first");
        }
        return INSTANCE;
    }

    private BillingManager(
            Context context,
            Set<String> consumableIds,
            Set<String> oneTimeIds,
            Set<String> subscriptionIds,
            boolean autoAckNonConsumables,
            boolean autoAckSubscriptions,
            boolean autoConsumeConsumables
    ) {
        this.appContext = context;
        this.consumableProductIds = Set.copyOf(consumableIds);
        this.oneTimeProductIds = Set.copyOf(oneTimeIds);
        this.subscriptionProductIds = Set.copyOf(subscriptionIds);
        this.autoAcknowledgeNonConsumables = autoAckNonConsumables;
        this.autoAcknowledgeSubscriptions = autoAckSubscriptions;
        this.autoConsumeConsumables = autoConsumeConsumables;

        this.pricePrefs = appContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
        );

        buildClient();
    }

    private void buildClient() {
        billingClient = BillingClient.newBuilder(appContext)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build()
                )
                .setListener(this)
                .build();
    }

    public void addListener(@NonNull BillingEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        if (isReady()) {
            listener.onBillingClientReady();

            Map<String, ProductDetails> snapshot = getAllProductDetails();
            if (!snapshot.isEmpty()) {
                listener.onProductDetailsLoaded(snapshot);
            }
        }
    }

    public void removeListener(@NonNull BillingEventListener listener) {
        listeners.remove(listener);
    }

    /** Start / restart connection to Play Billing. */
    public void startConnection() {
        if (billingClient.isReady()) {
            isReady = true;
            notifyBillingReady();
            refreshOwnership();
            refreshProductDetails();
            return;
        }

        billingClient.startConnection(this);
    }

    public boolean isReady() {
        return isReady && billingClient != null && billingClient.isReady();
    }

    /** Category lookup for a given productId. */
    @NonNull
    public ProductCategory getProductCategory(@NonNull String productId) {
        if (consumableProductIds.contains(productId)) {
            return ProductCategory.CONSUMABLE;
        } else if (oneTimeProductIds.contains(productId)) {
            return ProductCategory.ONE_TIME;
        } else if (subscriptionProductIds.contains(productId)) {
            return ProductCategory.SUBSCRIPTION;
        } else {
            return ProductCategory.UNKNOWN;
        }
    }

    /** Non-consumable license check. */
    public boolean isOneTimeProductOwned(@NonNull String productId) {
        Boolean owned = ownedOneTimeProducts.get(productId);
        return owned != null && owned;
    }

    /** Subscription active check. */
    public boolean isSubscriptionActive(@NonNull String productId) {
        Boolean active = activeSubscriptions.get(productId);
        return active != null && active;
    }

    /** Get cached ProductDetails for a given productId, or null. */
    @Nullable
    public ProductDetails getProductDetails(@NonNull String productId) {
        return productDetailsCache.get(productId);
    }

    /** Snapshot of all cached ProductDetails. */
    @NonNull
    public Map<String, ProductDetails> getAllProductDetails() {
        return new HashMap<>(productDetailsCache);
    }

    /** Force refresh of owned products (INAPP and SUBS). */
    public void refreshOwnership() {
        if (!isReady()) return;

        // INAPP (consumable + one-time)
        QueryPurchasesParams inappParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();

        billingClient.queryPurchasesAsync(inappParams, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                handleInappPurchaseSnapshot(purchases);
            } else {
                notifyBillingUnavailable(billingResult);
            }
        });

        // SUBS
        QueryPurchasesParams subsParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build();

        billingClient.queryPurchasesAsync(subsParams, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                handleSubscriptionSnapshot(purchases);
            } else {
                notifyBillingUnavailable(billingResult);
            }
        });
    }

    /** Force refresh ProductDetails for ALL configured SKUs. */
    public void refreshProductDetails() {
        if (!isReady()) return;

        List<QueryProductDetailsParams.Product> products = new ArrayList<>();

        for (String id : consumableProductIds) {
            products.add(
                    QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
            );
        }
        for (String id : oneTimeProductIds) {
            products.add(
                    QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
            );
        }
        for (String id : subscriptionProductIds) {
            products.add(
                    QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build()
            );
        }

        if (products.isEmpty()) {
            productDetailsCache.clear();
            notifyProductDetailsLoaded();
            return;
        }

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();

        billingClient.queryProductDetailsAsync(
                params,
                (billingResult, productDetailsResult) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        List<ProductDetails> productDetailsList =
                                productDetailsResult.getProductDetailsList();

                        productDetailsCache.clear();
                        for (ProductDetails pd : productDetailsList) {
                            productDetailsCache.put(pd.getProductId(), pd);
                        }

                        // Detect price changes before notifying listeners
                        detectPriceChanges(productDetailsCache);

                        notifyProductDetailsLoaded();
                    } else {
                        notifyBillingUnavailable(billingResult);
                    }
                }
        );
    }

    /**
     * High-level: launch a purchase for a productId.
     * Handles:
     * - Consumable INAPP (no offer token)
     * - One-time INAPP (no offer token)
     * - SUBS (uses first available offer by default)
     */
    @MainThread
    @NonNull
    public BillingResult launchPurchase(
            @NonNull Activity activity,
            @NonNull String productId
    ) {
        if (!isReady()) {
            return BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                    .setDebugMessage("BillingClient not ready")
                    .build();
        }

        ProductDetails pd = getProductDetails(productId);
        if (pd == null) {
            return BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                    .setDebugMessage("ProductDetails not loaded for " + productId)
                    .build();
        }

        ProductCategory category = getProductCategory(productId);
        String productType;

        switch (category) {
            case CONSUMABLE:
            case ONE_TIME:
                productType = BillingClient.ProductType.INAPP;
                break;
            case SUBSCRIPTION:
                productType = BillingClient.ProductType.SUBS;
                break;
            default:
                return BillingResult.newBuilder()
                        .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                        .setDebugMessage("Unknown product category for " + productId)
                        .build();
        }

        BillingFlowParams.ProductDetailsParams.Builder pdParamsBuilder =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd);

        if (BillingClient.ProductType.SUBS.equals(productType)) {
            // Simple default: pick the first subscription offer
            List<ProductDetails.SubscriptionOfferDetails> offers =
                    pd.getSubscriptionOfferDetails();
            if (offers == null || offers.isEmpty()) {
                return BillingResult.newBuilder()
                        .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                        .setDebugMessage("No subscription offers for " + productId)
                        .build();
            }
            String offerToken = offers.get(0).getOfferToken();
            pdParamsBuilder.setOfferToken(offerToken);
        }

        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                        Collections.singletonList(pdParamsBuilder.build())
                )
                .build();

        return billingClient.launchBillingFlow(activity, flowParams);
    }

    /** Explicit acknowledge helper if you want manual control. */
    public void acknowledgePurchase(
            @NonNull Purchase purchase,
            @Nullable AcknowledgePurchaseResponseListener listener
    ) {
        if (!isReady()) return;
        if (purchase.isAcknowledged()) return;

        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        if (listener == null) {
            listener = billingResult -> {
                // no-op
            };
        }

        billingClient.acknowledgePurchase(params, listener);
    }

    /** Explicit consume helper (if you disable auto-consume). */
    public void consumePurchase(
            @NonNull Purchase purchase,
            @Nullable ConsumeResponseListener listener
    ) {
        if (!isReady()) return;

        ConsumeParams params = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        if (listener == null) {
            listener = (billingResult, purchaseToken) -> {
                // no-op
            };
        }

        billingClient.consumeAsync(params, listener);
    }

    /** Optional helper to close client. */
    public void endConnection() {
        if (billingClient != null) {
            billingClient.endConnection();
        }
        isReady = false;
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            isReady = true;
            notifyBillingReady();
            refreshOwnership();
            refreshProductDetails();
        } else {
            isReady = false;
            notifyBillingUnavailable(billingResult);
        }
    }

    @Override
    public void onBillingServiceDisconnected() {
        isReady = false;

        new Handler(Looper.getMainLooper()).postDelayed(this::startConnection, 2000);


        BillingResult result = BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                .setDebugMessage("Service disconnected")
                .build();
        notifyBillingUnavailable(result);
    }

    @Override
    public void onPurchasesUpdated(
            @NonNull BillingResult billingResult,
            @Nullable List<Purchase> purchases
    ) {
        // Notify app of raw update
        for (BillingEventListener l : listeners) {
            l.onPurchasesUpdated(billingResult, purchases);
        }

        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handleSinglePurchase(purchase);
            }
        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            // user canceled – no special handling
        } else {
            notifyBillingUnavailable(billingResult);
        }
    }

    private void handleInappPurchaseSnapshot(@NonNull List<Purchase> purchases) {
        Map<String, Boolean> snapshotOneTime = new ConcurrentHashMap<>();

        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                continue;
            }

            for (String productId : purchase.getProducts()) {
                if (oneTimeProductIds.contains(productId)) {
                    snapshotOneTime.put(productId, true);
                }
            }
        }

        for (String id : oneTimeProductIds) {
            boolean newOwned = Boolean.TRUE.equals(snapshotOneTime.getOrDefault(id, false));
            boolean oldOwned = Boolean.TRUE.equals(ownedOneTimeProducts.getOrDefault(id, false));
            if (newOwned != oldOwned) {
                ownedOneTimeProducts.put(id, newOwned);
                notifyOneTimeOwnershipChanged(id, newOwned);
            }
        }
    }

    private void handleSubscriptionSnapshot(@NonNull List<Purchase> purchases) {
        Map<String, Boolean> snapshotSubs = new ConcurrentHashMap<>();

        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                continue;
            }

            for (String productId : purchase.getProducts()) {
                if (subscriptionProductIds.contains(productId)) {
                    snapshotSubs.put(productId, true);
                }
            }
        }

        for (String id : subscriptionProductIds) {
            boolean newActive = Boolean.TRUE.equals(snapshotSubs.getOrDefault(id, false));
            boolean oldActive = Boolean.TRUE.equals(activeSubscriptions.getOrDefault(id, false));
            if (newActive != oldActive) {
                activeSubscriptions.put(id, newActive);
                notifySubscriptionOwnershipChanged(id, newActive);
            }
        }
    }

    private void handleSinglePurchase(@NonNull Purchase purchase) {
        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            return;
        }

        List<String> productIds = purchase.getProducts();
        if (productIds == null || productIds.isEmpty()) return;

        String firstId = productIds.get(0);
        ProductCategory category = getProductCategory(firstId);

        switch (category) {
            case ONE_TIME:
                for (String productId : productIds) {
                    if (!oneTimeProductIds.contains(productId)) continue;
                    Boolean prev = ownedOneTimeProducts.put(productId, true);
                    if (prev == null || !prev) {
                        notifyOneTimeOwnershipChanged(productId, true);
                    }
                }
                if (autoAcknowledgeNonConsumables && !purchase.isAcknowledged()) {
                    acknowledgePurchase(purchase, null);
                }
                break;

            case CONSUMABLE:
                if (autoConsumeConsumables) {
                    consumePurchase(purchase, null);
                }
                break;

            case SUBSCRIPTION:
                for (String productId : productIds) {
                    if (!subscriptionProductIds.contains(productId)) continue;
                    Boolean prev = activeSubscriptions.put(productId, true);
                    if (prev == null || !prev) {
                        notifySubscriptionOwnershipChanged(productId, true);
                    }
                }
                if (autoAcknowledgeSubscriptions && !purchase.isAcknowledged()) {
                    acknowledgePurchase(purchase, null);
                }
                break;

            case UNKNOWN:
            default:
                // Not registered in our config
                break;
        }
    }

    /**
     * Extract price info (micros + currency) from ProductDetails.
     * Handles INAPP (one-time/consumable) and SUBS (first pricing phase).
     */
    @Nullable
    private PriceInfo extractPriceInfo(@NonNull ProductDetails pd) {
        // INAPP (one-time / consumable)
        ProductDetails.OneTimePurchaseOfferDetails oneTime =
                pd.getOneTimePurchaseOfferDetails();
        if (oneTime != null) {
            long micros = oneTime.getPriceAmountMicros();
            String currency = oneTime.getPriceCurrencyCode();
            if (currency == null) currency = "";
            return new PriceInfo(micros, currency);
        }

        // SUBS – take first pricing phase of first offer as the "current" price
        List<ProductDetails.SubscriptionOfferDetails> offers =
                pd.getSubscriptionOfferDetails();
        if (offers != null && !offers.isEmpty()) {
            ProductDetails.SubscriptionOfferDetails offer = offers.get(0);
            if (offer.getPricingPhases() != null &&
                    offer.getPricingPhases().getPricingPhaseList() != null &&
                    !offer.getPricingPhases().getPricingPhaseList().isEmpty()) {

                ProductDetails.PricingPhase phase =
                        offer.getPricingPhases().getPricingPhaseList().get(0);

                long micros = phase.getPriceAmountMicros();
                String currency = phase.getPriceCurrencyCode();
                if (currency == null) currency = "";
                return new PriceInfo(micros, currency);
            }
        }

        return null;
    }

    @Nullable
    private PriceInfo loadStoredPrice(@NonNull String productId) {
        long micros = pricePrefs.getLong(
                KEY_PRICE_MICROS_PREFIX + productId,
                Long.MIN_VALUE
        );
        if (micros == Long.MIN_VALUE) {
            return null; // no stored price yet
        }
        String currency = pricePrefs.getString(
                KEY_PRICE_CURRENCY_PREFIX + productId,
                ""
        );
        if (currency == null) currency = "";
        return new PriceInfo(micros, currency);
    }

    private void storePrice(@NonNull String productId, @NonNull PriceInfo info) {
        pricePrefs.edit()
                .putLong(KEY_PRICE_MICROS_PREFIX + productId, info.priceMicros())
                .putString(KEY_PRICE_CURRENCY_PREFIX + productId, info.currencyCode())
                .apply();
    }

    /**
     * Compare current ProductDetails prices against stored values and
     * fire onProductPriceChanged for any SKUs that differ.
     */
    private void detectPriceChanges(@NonNull Map<String, ProductDetails> latestDetails) {
        for (Map.Entry<String, ProductDetails> entry : latestDetails.entrySet()) {
            String productId = entry.getKey();
            ProductDetails pd = entry.getValue();

            PriceInfo newInfo = extractPriceInfo(pd);
            if (newInfo == null) {
                continue; // no price info, ignore
            }

            PriceInfo oldInfo = loadStoredPrice(productId);
            if (oldInfo == null) {
                // First time seeing this price; store and move on
                storePrice(productId, newInfo);
                continue;
            }

            boolean priceChanged =
                    (oldInfo.priceMicros() != newInfo.priceMicros()) ||
                            !oldInfo.currencyCode().equals(newInfo.currencyCode());

            if (priceChanged) {
                for (BillingEventListener l : listeners) {
                    l.onProductPriceChanged(
                            productId,
                            oldInfo.priceMicros(),
                            oldInfo.currencyCode(),
                            newInfo.priceMicros(),
                            newInfo.currencyCode()
                    );
                }
                storePrice(productId, newInfo);
            }
        }
    }

    private void notifyBillingReady() {
        for (BillingEventListener l : listeners) {
            l.onBillingClientReady();
        }
    }

    private void notifyBillingUnavailable(@NonNull BillingResult result) {
        for (BillingEventListener l : listeners) {
            l.onBillingClientUnavailable(result);
        }
    }

    private void notifyOneTimeOwnershipChanged(@NonNull String productId, boolean isOwned) {
        for (BillingEventListener l : listeners) {
            l.onOneTimeProductOwnershipChanged(productId, isOwned);
        }
    }

    private void notifySubscriptionOwnershipChanged(@NonNull String productId, boolean isActive) {
        for (BillingEventListener l : listeners) {
            l.onSubscriptionOwnershipChanged(productId, isActive);
        }
    }

    private void notifyProductDetailsLoaded() {
        Map<String, ProductDetails> copy = getAllProductDetails();
        for (BillingEventListener l : listeners) {
            l.onProductDetailsLoaded(copy);
        }
    }

    public static boolean isProbablyPlayServicesOrAccountIssue(@NonNull BillingResult result) {
        return switch (result.getResponseCode()) {
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                 BillingClient.BillingResponseCode.SERVICE_DISCONNECTED, BillingClient.BillingResponseCode.ERROR -> true;
            default -> false;
        };
    }

}