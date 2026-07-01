// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.monetization.play.PlayMonetization

/**
 * Google Play flavor: Google Mobile Ads plus a one-time "remove ads" purchase.
 *
 * These are Google's official **test** ad unit ids and a placeholder product id — replace them (and
 * the AdMob application id in this flavor's AndroidManifest) with your real values before release.
 */
object MonetizationProvider {
    private val config = PlayMonetization.Config(
        bannerAdUnitId = "ca-app-pub-3940256099942544/6300978111",       // AdMob test banner
        interstitialAdUnitId = "ca-app-pub-3940256099942544/1033173712", // AdMob test interstitial
        removeAdsProductId = "remove_ads",
    )

    fun create(context: Context): Monetization = PlayMonetization(context, config)
}
