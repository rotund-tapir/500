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
    fun create(context: Context): Monetization = FossMonetization(context, ProjectLinks.DONATION_URL)
}
