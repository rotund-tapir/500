// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import kotlinx.coroutines.flow.Flow

/** How quickly bot turns play out (the delay before each bot decision). */
enum class AnimationSpeed(val label: String, val botDelayMillis: Long) {
    SLOW("Slow", 1600),
    NORMAL("Normal", 800),
    FAST("Fast", 250),
    OFF("Off", 0);

    /** The next speed in the cycle Slow → Normal → Fast → Off → Slow. */
    fun next(): AnimationSpeed = entries[(ordinal + 1) % entries.size]
}

/**
 * Persisted user preferences. Backed per platform: Jetpack DataStore on Android
 * ([DataStoreSettingsRepository]), `localStorage` in the browser build.
 */
interface SettingsRepository {
    /** The persisted animation speed; [AnimationSpeed.NORMAL] when unset or unrecognised. */
    val animationSpeed: Flow<AnimationSpeed>

    suspend fun setAnimationSpeed(speed: AnimationSpeed)

    /** Whether new hands start sorted; false (deal order) when unset. */
    val sortHandByDefault: Flow<Boolean>

    suspend fun setSortHandByDefault(value: Boolean)

    /** House rule: whether Misère / Open Misère may be bid. Applies to new games; true when unset. */
    val misereEnabled: Flow<Boolean>

    suspend fun setMisereEnabled(value: Boolean)

    /** House rule: whether no-trump contracts may be bid. Applies to new games; true when unset. */
    val noTrumpsEnabled: Flow<Boolean>

    suspend fun setNoTrumpsEnabled(value: Boolean)

    /** Whether completed tricks stay on the felt until tapped away; false when unset. */
    val holdTricks: Flow<Boolean>

    suspend fun setHoldTricks(value: Boolean)

    /** Sound-effect volume, 0f (muted) to 1f; 0.7f when unset. */
    val soundVolume: Flow<Float>

    suspend fun setSoundVolume(value: Float)
}
