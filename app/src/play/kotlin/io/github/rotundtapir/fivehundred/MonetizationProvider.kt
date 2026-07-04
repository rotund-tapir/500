// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.app.Activity
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.monetization.play.PlayMonetization

/**
 * Google Play flavor: Google Mobile Ads plus a one-time "remove ads" purchase.
 *
 * Debug builds use Google's official **test** ad unit ids so development can never generate
 * invalid traffic against the real AdMob account; release builds use the real units.
 */
object MonetizationProvider {
    private val config = PlayMonetization.Config(
        bannerAdUnitId = if (BuildConfig.DEBUG) {
            "ca-app-pub-3940256099942544/6300978111" // AdMob test banner
        } else {
            "ca-app-pub-8530652887083578/4781827034"
        },
        interstitialAdUnitId = if (BuildConfig.DEBUG) {
            "ca-app-pub-3940256099942544/1033173712" // AdMob test interstitial
        } else {
            "ca-app-pub-8530652887083578/9928166330"
        },
        removeAdsProductId = "remove_ads",
        // Debug builds force the EEA consent form so the UMP flow is always exercisable.
        consentDebugGeographyEea = BuildConfig.DEBUG,
    )

    fun create(activity: Activity): Monetization = PlayMonetization(activity, config)
}
