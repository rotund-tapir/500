// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Build-specific values the shared UI needs, supplied by each entry point (they came from AGP's
 * BuildConfig when the UI lived in the Android app, which multiplatform code cannot see).
 */
data class AppConfig(
    /** Where "Submit feedback" goes: the GitHub issue tracker (FOSS/web) or a mailto (Play). */
    val feedbackUri: String,
)

/** Provided by [FiveHundredApp]; read where the UI needs a build-specific value. */
val LocalAppConfig = staticCompositionLocalOf<AppConfig> {
    error("LocalAppConfig not provided")
}
