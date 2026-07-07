// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** [SettingsRepository] backed by Jetpack DataStore — the Android implementation. */
class DataStoreSettingsRepository(context: Context) : SettingsRepository {
    private val dataStore = context.applicationContext.settingsDataStore

    override val animationSpeed: Flow<AnimationSpeed> = dataStore.data.map { preferences ->
        preferences[ANIMATION_SPEED_KEY]
            ?.let { stored -> AnimationSpeed.entries.find { it.name == stored } }
            ?: AnimationSpeed.NORMAL
    }

    override suspend fun setAnimationSpeed(speed: AnimationSpeed) {
        dataStore.edit { preferences -> preferences[ANIMATION_SPEED_KEY] = speed.name }
    }

    override val sortHandByDefault: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SORT_HAND_BY_DEFAULT_KEY] ?: false
    }

    override suspend fun setSortHandByDefault(value: Boolean) {
        dataStore.edit { preferences -> preferences[SORT_HAND_BY_DEFAULT_KEY] = value }
    }

    override val misereEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[MISERE_ENABLED_KEY] ?: true
    }

    override suspend fun setMisereEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[MISERE_ENABLED_KEY] = value }
    }

    override val noTrumpsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[NO_TRUMPS_ENABLED_KEY] ?: true
    }

    override suspend fun setNoTrumpsEnabled(value: Boolean) {
        dataStore.edit { preferences -> preferences[NO_TRUMPS_ENABLED_KEY] = value }
    }

    override val holdTricks: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[HOLD_TRICKS_KEY] ?: false
    }

    override suspend fun setHoldTricks(value: Boolean) {
        dataStore.edit { preferences -> preferences[HOLD_TRICKS_KEY] = value }
    }

    override val soundVolume: Flow<Float> = dataStore.data.map { preferences ->
        (preferences[SOUND_VOLUME_KEY] ?: 0.7f).coerceIn(0f, 1f)
    }

    override suspend fun setSoundVolume(value: Float) {
        dataStore.edit { preferences -> preferences[SOUND_VOLUME_KEY] = value.coerceIn(0f, 1f) }
    }

    private companion object {
        val ANIMATION_SPEED_KEY = stringPreferencesKey("animation_speed")
        val SORT_HAND_BY_DEFAULT_KEY = booleanPreferencesKey("sort_hand_by_default")
        val MISERE_ENABLED_KEY = booleanPreferencesKey("misere_enabled")
        val NO_TRUMPS_ENABLED_KEY = booleanPreferencesKey("no_trumps_enabled")
        val HOLD_TRICKS_KEY = booleanPreferencesKey("hold_tricks")
        val SOUND_VOLUME_KEY = floatPreferencesKey("sound_volume")
    }
}
