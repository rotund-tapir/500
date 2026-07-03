// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.rotundtapir.fivehundred.engine.PlayerView

/**
 * The game's sound vocabulary. All effects are short one-shots from Kenney's CC0 "Casino Audio"
 * pack (kenney.nl), bundled as OGG in `res/raw`.
 */
enum class SoundEffect {
    /** A card landing on the felt (a play into the current trick). */
    CARD_PLACE,

    /** A dealt packet arriving at its destination. */
    CARD_SLIDE,

    /** One riffle of the deck during the shuffle stage. */
    SHUFFLE,

    /** A completed trick being swept up by its winner. */
    TRICK_TAKEN,

    /** The hand-result dialog appearing (chips changing hands). */
    SCORE,
}

/**
 * A small [SoundPool]-backed effect player. Loads every effect once up front; [play] picks a
 * random variant where an effect has several. [volume] is 0f..1f — at 0 the play is skipped
 * entirely (no stream is even started). Call [release] when done.
 */
class SoundManager(context: Context) {
    private val appContext = context.applicationContext

    /** Playback volume, 0f (silent — playback skipped) to 1f. */
    var volume: Float = 0f

    // The SoundPool is created lazily on the FIRST audible play: at volume 0 (e.g. instrumentation
    // runs on a -no-audio emulator, where native playback is flaky enough to kill the process) no
    // native audio resources are ever touched.
    private var soundPool: SoundPool? = null
    private var soundIds: Map<SoundEffect, List<Int>> = emptyMap()

    private fun ensurePool(): SoundPool = soundPool ?: SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
        .also { pool ->
            soundPool = pool
            soundIds = mapOf(
                SoundEffect.CARD_PLACE to listOf(R.raw.card_place_1, R.raw.card_place_2, R.raw.card_place_3),
                SoundEffect.CARD_SLIDE to listOf(R.raw.card_slide_1, R.raw.card_slide_2),
                SoundEffect.SHUFFLE to listOf(R.raw.card_shuffle),
                SoundEffect.TRICK_TAKEN to listOf(R.raw.trick_take_1, R.raw.trick_take_2),
                SoundEffect.SCORE to listOf(R.raw.score_chips),
            ).mapValues { (_, resources) -> resources.map { pool.load(appContext, it, 1) } }
        }

    fun play(effect: SoundEffect) {
        val v = volume.coerceIn(0f, 1f)
        if (v <= 0f) return
        val pool = ensurePool()
        pool.play(soundIds.getValue(effect).random(), v, v, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
    }
}

/**
 * State-driven sound triggers: observes [view] transitions (against the previously seen view) and
 * plays the matching effect. Owns its [SoundManager] (released on dispose). Wire it next to the
 * game screen with the current [PlayerView] and the persisted volume setting; a null [view] is a
 * no-op.
 *
 * Triggers:
 *  - the current trick grew → [SoundEffect.CARD_PLACE]
 *  - the trick number increased → [SoundEffect.TRICK_TAKEN]
 *  - a (new) hand result appeared → [SoundEffect.SCORE]
 *
 * Deal/shuffle sounds are not derived here — they fire from the dealing animation via
 * `DealAnimationState.soundHook`.
 */
// One SoundPool for the whole process: activity recreation (and each instrumentation test's
// fresh activity) must NOT churn native audio resources — rapid create/release cycles wedge the
// emulator's audio stack and crash the instrumented process. Android reclaims it at process death.
private var sharedSoundManager: SoundManager? = null

@Composable
fun GameSoundEffects(view: PlayerView?, volume: Float): (SoundEffect) -> Unit {
    val context = LocalContext.current
    val manager = remember {
        sharedSoundManager ?: SoundManager(context.applicationContext).also { sharedSoundManager = it }
    }
    SideEffect { manager.volume = volume }

    var previous by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(view) {
        val prev = previous
        previous = view
        if (view == null || prev == null) return@LaunchedEffect
        if (view.currentTrick.size > prev.currentTrick.size) {
            manager.play(SoundEffect.CARD_PLACE)
        }
        if (view.trickNumber > prev.trickNumber) {
            manager.play(SoundEffect.TRICK_TAKEN)
        }
        val result = view.lastHandResult
        if (result != null && result != prev.lastHandResult) {
            manager.play(SoundEffect.SCORE)
        }
    }

    // Shared with imperative call sites (the dealing animation's soundHook).
    return remember(manager) { { effect: SoundEffect -> manager.play(effect) } }
}
