// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.theme.CardkitTheme

class MainActivity : ComponentActivity() {
    companion object {
        /** Intent extra overriding the game seed — set by instrumentation tests for reproducibility. */
        const val EXTRA_SEED = "io.github.rotundtapir.fivehundred.SEED"

        /**
         * Intent extra (an [AnimationSpeed] name) overriding the persisted animation speed — set by
         * instrumentation tests to run without bot delays.
         */
        const val EXTRA_ANIMATION_SPEED = "io.github.rotundtapir.fivehundred.ANIMATION_SPEED"

        /** Intent extra (Float) overriding the persisted sound volume — tests pass 0f. */
        const val EXTRA_SOUND_VOLUME = "io.github.rotundtapir.fivehundred.SOUND_VOLUME"
    }

    private lateinit var monetization: Monetization

    private fun newGameSeed(): Long =
        if (intent?.hasExtra(EXTRA_SEED) == true) intent.getLongExtra(EXTRA_SEED, 0)
        else System.currentTimeMillis()

    /** The sound volume forced by the launching intent, or null to use the persisted setting. */
    private fun soundVolumeOverride(): Float? =
        if (intent?.hasExtra(EXTRA_SOUND_VOLUME) == true) intent.getFloatExtra(EXTRA_SOUND_VOLUME, 0f) else null

    /** The animation speed forced by the launching intent, or null to use the persisted setting. */
    private fun animationSpeedOverride(): AnimationSpeed? =
        AnimationSpeed.fromName(intent?.getStringExtra(EXTRA_ANIMATION_SPEED))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monetization = MonetizationProvider.create(this)
        setContent {
            CardkitTheme {
                FiveHundredApp(
                    monetization = monetization,
                    settings = remember { DataStoreSettingsRepository(applicationContext) },
                    appConfig = AppConfig(feedbackUri = BuildConfig.FEEDBACK_URI),
                    nextSeed = ::newGameSeed,
                    animationSpeedOverride = animationSpeedOverride(),
                    soundVolumeOverride = soundVolumeOverride(),
                )
            }
        }
    }

    override fun onDestroy() {
        monetization.dispose()
        super.onDestroy()
    }
}
