package com.vaishnava.alarm

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {
    private const val TAG = "AdManager"
    private const val AD_UNIT_ID = "ca-app-pub-7229295499236355/6591361135"
    private const val TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    // Volatile for thread-safety across callbacks — all callbacks run on main looper though.
    @Volatile
    private var interstitialAd: InterstitialAd? = null

    @Volatile
    private var isLoading: Boolean = false

    // Handler for retry delays
    private val handler = Handler(Looper.getMainLooper())

    // Common FullScreenContentCallback attached to each loaded ad so we always handle lifecycle
    private val commonFullScreenCallback = object : FullScreenContentCallback() {
        override fun onAdDismissedFullScreenContent() {
            Log.d(TAG, "commonCallback: ad dismissed -> clearing and preloading next")
            interstitialAd = null
            // preload next ad
            // (use application context - caller will pass if needed)
            // Note: we cannot access activity here; user code should call preload(context) after onAdDismissed if needed.
        }

        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
            Log.e(TAG, "commonCallback: failed to show ad (${adError.code}) ${adError.message}")
            interstitialAd = null
            // allow external callers to trigger preload; we'll also schedule a retry in show logic
        }

        override fun onAdShowedFullScreenContent() {
            Log.d(TAG, "commonCallback: ad showed")
            // don't clear interstitialAd here; we'll clear explicitly on dismissal/failure to keep flows consistent
        }
    }

    /**
     * Preload an interstitial ad if not already loading/available.
     * Use TEST_AD_UNIT_ID while debugging; set to AD_UNIT_ID for production.
     */
    fun preload(context: Context, useTestAd: Boolean = true) {
        // If an ad is already loaded, nothing to do.
        if (interstitialAd != null) {
            Log.d(TAG, "preload: ad already available; skipping load")
            return
        }
        // If a load is already in progress, don't start another.
        if (isLoading) {
            Log.d(TAG, "preload: load already in progress; skipping")
            return
        }

        isLoading = true
        val adUnitId = if (useTestAd) TEST_AD_UNIT_ID else AD_UNIT_ID
        val request = AdRequest.Builder().build()
        Log.d(TAG, "preload: start loading (useTestAd=$useTestAd)")

        InterstitialAd.load(
            context,
            adUnitId,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isLoading = false
                    interstitialAd = ad
                    // Attach the common callback so every ad has lifecycle handled
                    interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            Log.d(TAG, "ad.fullScreenContentCallback: dismissed -> clearing & scheduling preload")
                            interstitialAd = null
                            // schedule preload after small delay to avoid immediate race
                            handler.postDelayed({ preload(context, useTestAd) }, 500)
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            Log.e(TAG, "ad.fullScreenContentCallback: failed to show (${adError.code}) ${adError.message}")
                            interstitialAd = null
                            handler.postDelayed({ preload(context, useTestAd) }, 1000)
                        }

                        override fun onAdShowedFullScreenContent() {
                            Log.d(TAG, "ad.fullScreenContentCallback: showed")
                            // Keep interstitialAd intact until dismissed callback
                        }
                    }
                    Log.d(TAG, "onAdLoaded: interstitial ready")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoading = false
                    interstitialAd = null
                    Log.e(TAG, "onAdFailedToLoad: code=${error.code} domain=${error.domain} msg=${error.message}")
                    // Exponential/backoff or fixed retry — keep it simple and retry after 10s
                    handler.postDelayed({ preload(context, useTestAd) }, 10000)
                }
            }
        )
    }

    /**
     * Show ad if available. If not available, logs & triggers preload.
     * Non-blocking: returns immediately; on-screen ad lifecycle handled by callbacks above.
     */
    fun showIfAvailable(activity: Activity) {
        val ad = interstitialAd
        if (ad != null) {
            try {
                Log.d(TAG, "showIfAvailable: showing interstitial")
                ad.show(activity)
            } catch (t: Throwable) {
                Log.e(TAG, "showIfAvailable: exception while showing ad: ${t.message}")
                interstitialAd = null
                // Try to reload for next time
                preload(activity.applicationContext)
            }
        } else {
            Log.d(TAG, "showIfAvailable: no ad ready -> triggering preload")
            preload(activity.applicationContext)
        }
    }

    /**
     * Show an interstitial, and if it isn't available call proceed immediately.
     * proceed() will always be called exactly once (either after dismissal or immediately if no ad).
     */
    fun showBeforeAction(activity: Activity, proceed: () -> Unit) {
        val ad = interstitialAd
        if (ad != null) {
            Log.d(TAG, "showBeforeAction: ad ready - showing")
            // Attach a per-show wrapper callback that ensures proceed() is always invoked exactly once.
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    Log.d(TAG, "showBeforeAction: ad dismissed -> proceed()")
                    interstitialAd = null
                    try { proceed() } catch (t: Throwable) { Log.e(TAG, "proceed() threw: ${t.message}") }
                    // Preload next
                    handler.postDelayed({ preload(activity.applicationContext) }, 500)
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.e(TAG, "showBeforeAction: failed to show (${adError.code}) ${adError.message} -> proceed()")
                    interstitialAd = null
                    try { proceed() } catch (t: Throwable) { Log.e(TAG, "proceed() threw: ${t.message}") }
                    handler.postDelayed({ preload(activity.applicationContext) }, 1000)
                }

                override fun onAdShowedFullScreenContent() {
                    Log.d(TAG, "showBeforeAction: ad showed")
                    // nothing else here; dismissal callback will handle proceed()
                }
            }

            try {
                ad.show(activity)
            } catch (t: Throwable) {
                Log.e(TAG, "showBeforeAction: show() threw: ${t.message} -> fallback to proceed()")
                interstitialAd = null
                try { proceed() } catch (t2: Throwable) { Log.e(TAG, "proceed() threw: ${t2.message}") }
                preload(activity.applicationContext)
            }
        } else {
            Log.d(TAG, "showBeforeAction: no ad -> proceed immediately and preload")
            try { proceed() } catch (t: Throwable) { Log.e(TAG, "proceed() threw: ${t.message}") }
            preload(activity.applicationContext)
        }
    }

    // Small helper to inspect state while debugging
    fun getAdState(): String {
        return when {
            interstitialAd != null -> "Ad ready"
            isLoading -> "Loading..."
            else -> "No ad, not loading"
        }
    }
}
