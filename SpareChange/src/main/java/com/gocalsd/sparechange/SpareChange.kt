package com.gocalsd.sparechange

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.*
import com.gocalsd.sparechange.listener.BillingEventListener
import com.gocalsd.sparechange.model.PriceInfo
import com.gocalsd.sparechange.model.ProductCategory
import com.gocalsd.sparechange.model.SubscriptionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

class SpareChange private constructor(
    private val appContext: Context,
    consumableIds: Set<String>,
    oneTimeIds: Set<String>,
    subscriptionIds: Set<String>,
    private val autoAcknowledgeNonConsumables: Boolean,
    private val autoAcknowledgeSubscriptions: Boolean,
    private val autoConsumeConsumables: Boolean
) : PurchasesUpdatedListener, BillingClientStateListener, DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var billingClient: BillingClient? = null
    
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val consumableProductIds = consumableIds.toSet()
    private val oneTimeProductIds = oneTimeIds.toSet()
    private val subscriptionProductIds = subscriptionIds.toSet()

    private val listeners = CopyOnWriteArrayList<BillingEventListener>()

    private val _ownedOneTimeProducts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val ownedOneTimeProducts: StateFlow<Map<String, Boolean>> = _ownedOneTimeProducts.asStateFlow()

    private val _activeSubscriptions = MutableStateFlow<Map<String, SubscriptionStatus>>(emptyMap())
    val activeSubscriptions: StateFlow<Map<String, SubscriptionStatus>> = _activeSubscriptions.asStateFlow()

    private val _pendingProductIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingProductIds: StateFlow<Set<String>> = _pendingProductIds.asStateFlow()

    private val _productDetailsCache = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetailsCache: StateFlow<Map<String, ProductDetails>> = _productDetailsCache.asStateFlow()

    private var isLaunchingFlow = false
    private var reconnectRetryCount = 0

    private val pricePrefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SpareChange"
        private const val PREFS_NAME = "billinghelper_prices"
        private const val KEY_PRICE_MICROS_PREFIX = "price_micros_"
        private const val KEY_PRICE_CURRENCY_PREFIX = "price_currency_"
        private const val MAX_RECONNECT_DELAY_MS = 30000L

        @Volatile
        private var INSTANCE: SpareChange? = null

        @JvmStatic
        fun init(
            context: Context,
            consumableIds: Set<String>,
            oneTimeIds: Set<String>,
            subscriptionIds: Set<String>,
            autoAckNonConsumables: Boolean = true,
            autoAckSubscriptions: Boolean = true,
            autoConsumeConsumables: Boolean = true
        ): SpareChange {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpareChange(
                    context.applicationContext,
                    consumableIds,
                    oneTimeIds,
                    subscriptionIds,
                    autoAckNonConsumables,
                    autoAckSubscriptions,
                    autoConsumeConsumables
                ).also { 
                    INSTANCE = it
                    ProcessLifecycleOwner.get().lifecycle.addObserver(it)
                }
            }
        }

        @JvmStatic
        fun getInstance(): SpareChange {
            return INSTANCE ?: throw IllegalStateException("SpareChange.init() must be called first")
        }
    }

    init {
        buildClient()
    }

    private fun buildClient() {
        billingClient = BillingClient.newBuilder(appContext)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .setListener(this)
            .build()
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        if (_isReady.value) {
            refreshOwnership()
        }
    }

    fun addListener(listener: BillingEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        if (_isReady.value) {
            listener.onBillingClientReady()
            val snapshot = _productDetailsCache.value
            if (snapshot.isNotEmpty()) {
                listener.onProductDetailsLoaded(snapshot)
            }
        }
    }

    fun removeListener(listener: BillingEventListener) {
        listeners.remove(listener)
    }

    fun startConnection() {
        if (billingClient?.isReady == true) {
            _isReady.value = true
            notifyBillingReady()
            refreshOwnership()
            refreshProductDetails()
            return
        }
        billingClient?.startConnection(this)
    }

    fun getProductCategory(productId: String): ProductCategory {
        return when {
            consumableProductIds.contains(productId) -> ProductCategory.CONSUMABLE
            oneTimeProductIds.contains(productId) -> ProductCategory.ONE_TIME
            subscriptionProductIds.contains(productId) -> ProductCategory.SUBSCRIPTION
            else -> ProductCategory.UNKNOWN
        }
    }

    fun isOneTimeProductOwned(productId: String): Boolean = _ownedOneTimeProducts.value[productId] == true

    fun isSubscriptionActive(productId: String): Boolean = _activeSubscriptions.value[productId]?.isActive == true

    /**
     * Get the full subscription status for a given productId.
     */
    fun getSubscriptionStatus(productId: String): SubscriptionStatus? = _activeSubscriptions.value[productId]

    fun isPurchasePending(productId: String): Boolean = _pendingProductIds.value.contains(productId)

    fun getProductDetails(productId: String): ProductDetails? = _productDetailsCache.value[productId]

    fun refreshOwnership() {
        if (billingClient?.isReady != true) return

        queryPurchases(BillingClient.ProductType.INAPP) { purchases ->
            handleInappPurchaseSnapshot(purchases)
        }

        queryPurchases(BillingClient.ProductType.SUBS) { purchases ->
            handleSubscriptionSnapshot(purchases)
        }
    }

    private fun queryPurchases(productType: String, callback: (List<Purchase>) -> Unit) {
        val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        billingClient?.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                callback(purchases)
            } else {
                notifyBillingUnavailable(result)
            }
        }
    }

    fun refreshProductDetails() {
        if (billingClient?.isReady != true) return

        val products = mutableListOf<QueryProductDetailsParams.Product>()
        
        (consumableProductIds + oneTimeProductIds).forEach { id ->
            products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build())
        }
        
        subscriptionProductIds.forEach { id ->
            products.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build())
        }

        if (products.isEmpty()) {
            _productDetailsCache.value = emptyMap()
            notifyProductDetailsLoaded()
            return
        }

        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient?.queryProductDetailsAsync(params) { result, productDetailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val detailsList = productDetailsResult.productDetailsList ?: emptyList()
                val newCache = detailsList.associateBy { it.productId }
                
                detectPriceChanges(newCache)
                _productDetailsCache.value = newCache
                notifyProductDetailsLoaded()
            } else {
                notifyBillingUnavailable(result)
            }
        }
    }

    @MainThread
    fun launchPurchase(
        activity: Activity,
        productId: String,
        offerToken: String? = null
    ): BillingResult {
        val client = billingClient
        if (client == null || !client.isReady) {
            return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                .setDebugMessage("BillingClient not ready")
                .build()
        }

        if (isLaunchingFlow) {
            return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                .setDebugMessage("A purchase flow is already in progress")
                .build()
        }

        if (isOneTimeProductOwned(productId) || isSubscriptionActive(productId)) {
            return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
                .setDebugMessage("Product already owned: $productId")
                .build()
        }

        if (isPurchasePending(productId)) {
            return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                .setDebugMessage("Purchase is pending for $productId")
                .build()
        }

        val pd = getProductDetails(productId) ?: return BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
            .setDebugMessage("ProductDetails not loaded for $productId")
            .build()

        val productType = when (getProductCategory(productId)) {
            ProductCategory.CONSUMABLE, ProductCategory.ONE_TIME -> BillingClient.ProductType.INAPP
            ProductCategory.SUBSCRIPTION -> BillingClient.ProductType.SUBS
            else -> return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                .setDebugMessage("Unknown product category for $productId")
                .build()
        }

        val pdParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd)

        if (productType == BillingClient.ProductType.SUBS) {
            val token = offerToken ?: pd.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (token == null) {
                return BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
                    .setDebugMessage("No subscription offers for $productId")
                    .build()
            }
            pdParamsBuilder.setOfferToken(token)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(pdParamsBuilder.build()))
            .build()

        isLaunchingFlow = true
        return try {
            client.launchBillingFlow(activity, flowParams)
        } finally {
            isLaunchingFlow = false
        }
    }

    fun acknowledgePurchase(purchase: Purchase, listener: AcknowledgePurchaseResponseListener? = null) {
        if (billingClient?.isReady != true || purchase.isAcknowledged) return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken())
            .build()

        billingClient?.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchase.products.forEach { productId ->
                    listeners.forEach { it.onPurchaseAcknowledged(productId, result) }
                }
            }
            listener?.onAcknowledgePurchaseResponse(result)
        }
    }

    fun consumePurchase(purchase: Purchase, listener: ConsumeResponseListener? = null) {
        if (billingClient?.isReady != true) return

        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken())
            .build()

        billingClient?.consumeAsync(params) { result, token ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchase.products.forEach { productId ->
                    listeners.forEach { it.onPurchaseConsumed(productId, result) }
                }
            }
            listener?.onConsumeResponse(result, token)
        }
    }

    fun endConnection() {
        billingClient?.endConnection()
        _isReady.value = false
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            _isReady.value = true
            reconnectRetryCount = 0
            notifyBillingReady()
            refreshOwnership()
            refreshProductDetails()
        } else {
            _isReady.value = false
            notifyBillingUnavailable(billingResult)
        }
    }

    override fun onBillingServiceDisconnected() {
        _isReady.value = false
        notifyBillingUnavailable(BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
            .setDebugMessage("Service disconnected")
            .build())
        
        // Exponential backoff for reconnection
        val delay = (2.0.pow(reconnectRetryCount.toDouble()) * 1000).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectRetryCount++
        
        scope.launch {
            delay(delay)
            startConnection()
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        listeners.forEach { it.onPurchasesUpdated(billingResult, purchases) }

        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handleSinglePurchase(it) }
        } else if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            notifyBillingUnavailable(billingResult)
        }
    }

    private fun handleInappPurchaseSnapshot(purchases: List<Purchase>) {
        val snapshotOneTime = mutableMapOf<String, Boolean>()
        val currentPending = mutableSetOf<String>()

        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                currentPending.addAll(purchase.products)
            } else if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                purchase.products.forEach { id ->
                    if (oneTimeProductIds.contains(id)) {
                        snapshotOneTime[id] = true
                    }
                }
            }
        }

        _pendingProductIds.update { it + currentPending }
        
        oneTimeProductIds.forEach { id ->
            val isOwned = snapshotOneTime[id] == true
            val wasOwned = _ownedOneTimeProducts.value[id] == true
            if (isOwned != wasOwned) {
                _ownedOneTimeProducts.update { it + (id to isOwned) }
                notifyOneTimeOwnershipChanged(id, isOwned)
            }
        }
    }

    private fun handleSubscriptionSnapshot(purchases: List<Purchase>) {
        val snapshotSubs = mutableMapOf<String, SubscriptionStatus>()
        val currentPending = mutableSetOf<String>()

        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                currentPending.addAll(purchase.products)
            } else if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                purchase.products.forEach { id ->
                    if (subscriptionProductIds.contains(id)) {
                        snapshotSubs[id] = SubscriptionStatus(
                            productId = id,
                            isActive = true,
                            isAutoRenewing = purchase.isAutoRenewing,
                            purchaseToken = purchase.purchaseToken,
                            purchaseTime = purchase.purchaseTime
                        )
                    }
                }
            }
        }

        _pendingProductIds.update { it + currentPending }

        subscriptionProductIds.forEach { id ->
            val newStatus = snapshotSubs[id]
            val oldStatus = _activeSubscriptions.value[id]
            
            if (newStatus != oldStatus) {
                _activeSubscriptions.update { it + (id to (newStatus ?: SubscriptionStatus(id, false, false, "", 0L))) }
                notifySubscriptionOwnershipChanged(id, newStatus?.isActive == true)
            }
        }
    }

    private fun handleSinglePurchase(purchase: Purchase) {
        val productIds = purchase.products
        if (productIds.isEmpty()) return

        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            _pendingProductIds.update { it + productIds }
            return
        }

        _pendingProductIds.update { it - productIds.toSet() }

        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        val firstId = productIds[0]
        when (getProductCategory(firstId)) {
            ProductCategory.ONE_TIME -> {
                productIds.filter { oneTimeProductIds.contains(it) }.forEach { id ->
                    if (_ownedOneTimeProducts.value[id] != true) {
                        _ownedOneTimeProducts.update { it + (id to true) }
                        notifyOneTimeOwnershipChanged(id, true)
                    }
                }
                if (autoAcknowledgeNonConsumables && !purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }
            }
            ProductCategory.CONSUMABLE -> {
                if (autoConsumeConsumables) {
                    consumePurchase(purchase)
                }
            }
            ProductCategory.SUBSCRIPTION -> {
                productIds.filter { subscriptionProductIds.contains(it) }.forEach { id ->
                    val oldStatus = _activeSubscriptions.value[id]
                    if (oldStatus?.isActive != true) {
                        _activeSubscriptions.update { it + (id to SubscriptionStatus(
                            productId = id,
                            isActive = true,
                            isAutoRenewing = purchase.isAutoRenewing,
                            purchaseToken = purchase.purchaseToken,
                            purchaseTime = purchase.purchaseTime
                        )) }
                        notifySubscriptionOwnershipChanged(id, true)
                    }
                }
                if (autoAcknowledgeSubscriptions && !purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }
            }
            else -> {}
        }
    }

    private fun extractPriceInfo(pd: ProductDetails): PriceInfo? {
        pd.oneTimePurchaseOfferDetails?.let {
            return PriceInfo(it.priceAmountMicros, it.priceCurrencyCode ?: "")
        }
        pd.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let {
            return PriceInfo(it.priceAmountMicros, it.priceCurrencyCode ?: "")
        }
        return null
    }

    private fun loadStoredPrice(productId: String): PriceInfo? {
        val micros = pricePrefs.getLong(KEY_PRICE_MICROS_PREFIX + productId, Long.MIN_VALUE)
        if (micros == Long.MIN_VALUE) return null
        val currency = pricePrefs.getString(KEY_PRICE_CURRENCY_PREFIX + productId, "") ?: ""
        return PriceInfo(micros, currency)
    }

    private fun storePrice(productId: String, info: PriceInfo) {
        pricePrefs.edit()
            .putLong(KEY_PRICE_MICROS_PREFIX + productId, info.priceMicros)
            .putString(KEY_PRICE_CURRENCY_PREFIX + productId, info.currencyCode)
            .apply()
    }

    private fun detectPriceChanges(latestDetails: Map<String, ProductDetails>) {
        latestDetails.forEach { (productId, pd) ->
            val newInfo = extractPriceInfo(pd) ?: return@forEach
            val oldInfo = loadStoredPrice(productId)

            if (oldInfo == null) {
                storePrice(productId, newInfo)
                return@forEach
            }

            if (oldInfo.priceMicros != newInfo.priceMicros || oldInfo.currencyCode != newInfo.currencyCode) {
                listeners.forEach {
                    it.onProductPriceChanged(
                        productId, oldInfo.priceMicros, oldInfo.currencyCode,
                        newInfo.priceMicros, newInfo.currencyCode
                    )
                }
                storePrice(productId, newInfo)
            }
        }
    }

    private fun notifyBillingReady() = listeners.forEach { it.onBillingClientReady() }
    private fun notifyBillingUnavailable(result: BillingResult) = listeners.forEach { it.onBillingClientUnavailable(result) }
    private fun notifyOneTimeOwnershipChanged(id: String, owned: Boolean) = listeners.forEach { it.onOneTimeProductOwnershipChanged(id, owned) }
    private fun notifySubscriptionOwnershipChanged(id: String, active: Boolean) = listeners.forEach { it.onSubscriptionOwnershipChanged(id, active) }
    private fun notifyProductDetailsLoaded() = listeners.forEach { it.onProductDetailsLoaded(_productDetailsCache.value) }
}
