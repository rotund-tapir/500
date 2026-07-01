// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.monetization.foss.FossMonetization

/**
 * FOSS flavor: no ads, no proprietary dependencies. "Support development" opens a donation page.
 * This is the flavor F-Droid builds.
 */
object MonetizationProvider {
    // TODO: set to your real donation page (e.g. Liberapay / GitHub Sponsors) before release.
    private const val DONATION_URL = "https://liberapay.com/rotund-tapir"

    fun create(context: Context): Monetization = FossMonetization(DONATION_URL)
}
