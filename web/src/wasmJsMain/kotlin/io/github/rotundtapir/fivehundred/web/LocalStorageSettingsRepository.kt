// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.web

import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.SettingsRepository
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [SettingsRepository] backed by the browser's `localStorage`. Each setting is one
 * `settings.<key>` entry, using the same key names as the Android DataStore implementation.
 * Values live in a per-setting [MutableStateFlow] seeded from storage, so reads are synchronous
 * and the Flow surface behaves like DataStore's.
 */
class LocalStorageSettingsRepository : SettingsRepository {

    private fun stored(key: String): String? = localStorage.getItem("settings.$key")

    private fun store(key: String, value: String) {
        localStorage.setItem("settings.$key", value)
    }

    private val animationSpeedFlow = MutableStateFlow(
        stored(ANIMATION_SPEED_KEY)
            ?.let { stored -> AnimationSpeed.entries.find { it.name == stored } }
            ?: AnimationSpeed.NORMAL,
    )
    override val animationSpeed: Flow<AnimationSpeed> = animationSpeedFlow

    override suspend fun setAnimationSpeed(speed: AnimationSpeed) {
        store(ANIMATION_SPEED_KEY, speed.name)
        animationSpeedFlow.value = speed
    }

    private val sortHandByDefaultFlow = MutableStateFlow(stored(SORT_HAND_BY_DEFAULT_KEY)?.toBoolean() ?: false)
    override val sortHandByDefault: Flow<Boolean> = sortHandByDefaultFlow

    override suspend fun setSortHandByDefault(value: Boolean) {
        store(SORT_HAND_BY_DEFAULT_KEY, value.toString())
        sortHandByDefaultFlow.value = value
    }

    private val misereEnabledFlow = MutableStateFlow(stored(MISERE_ENABLED_KEY)?.toBoolean() ?: true)
    override val misereEnabled: Flow<Boolean> = misereEnabledFlow

    override suspend fun setMisereEnabled(value: Boolean) {
        store(MISERE_ENABLED_KEY, value.toString())
        misereEnabledFlow.value = value
    }

    private val noTrumpsEnabledFlow = MutableStateFlow(stored(NO_TRUMPS_ENABLED_KEY)?.toBoolean() ?: true)
    override val noTrumpsEnabled: Flow<Boolean> = noTrumpsEnabledFlow

    override suspend fun setNoTrumpsEnabled(value: Boolean) {
        store(NO_TRUMPS_ENABLED_KEY, value.toString())
        noTrumpsEnabledFlow.value = value
    }

    private val holdTricksFlow = MutableStateFlow(stored(HOLD_TRICKS_KEY)?.toBoolean() ?: false)
    override val holdTricks: Flow<Boolean> = holdTricksFlow

    override suspend fun setHoldTricks(value: Boolean) {
        store(HOLD_TRICKS_KEY, value.toString())
        holdTricksFlow.value = value
    }

    private val soundVolumeFlow = MutableStateFlow((stored(SOUND_VOLUME_KEY)?.toFloatOrNull() ?: 0.7f).coerceIn(0f, 1f))
    override val soundVolume: Flow<Float> = soundVolumeFlow

    override suspend fun setSoundVolume(value: Float) {
        val coerced = value.coerceIn(0f, 1f)
        store(SOUND_VOLUME_KEY, coerced.toString())
        soundVolumeFlow.value = coerced
    }

    private companion object {
        const val ANIMATION_SPEED_KEY = "animation_speed"
        const val SORT_HAND_BY_DEFAULT_KEY = "sort_hand_by_default"
        const val MISERE_ENABLED_KEY = "misere_enabled"
        const val NO_TRUMPS_ENABLED_KEY = "no_trumps_enabled"
        const val HOLD_TRICKS_KEY = "hold_tricks"
        const val SOUND_VOLUME_KEY = "sound_volume"
    }
}
