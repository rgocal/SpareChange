package com.gocalsd.sparechange.activity;

import android.app.Activity;

import com.gocalsd.sparechange.listener.BillingEventListener;

import java.util.List;
import java.util.Map;

public class DemoActivity extends Activity implements BillingEventListener {

    @Override
    public void onBillingClientReady() {

    }

    @Override
    public void onOneTimeProductOwnershipChanged(String productId, boolean isOwned) {

    }

    @Override
    public void onSubscriptionOwnershipChanged(String productId, boolean isActive) {

    }

    @Override
    public void onProductPriceChanged(String productId, long oldPriceMicros, String oldCurrency, long newPriceMicros, String newCurrency) {

    }

    @Override
    public void onProductDetailsLoaded(Map<String, com.android.billingclient.api.ProductDetails> productDetailsMap) {

    }

    @Override
    public void onPurchasesUpdated(com.android.billingclient.api.BillingResult billingResult, List<com.android.billingclient.api.Purchase> purchases) {

    }

    @Override
    public void onBillingClientUnavailable(com.android.billingclient.api.BillingResult billingResult) {

    }
}
