// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.runtime.Composable

/**
 * Plays the pre-generated tutorial narration clips by resource URI (from `Res.getUri`).
 *
 * Implementations are lazy: no platform audio object exists until the first [play], so a run with
 * narration muted (or master volume 0 — the instrumented `-no-audio` emulator) never touches
 * native audio.
 */
interface NarrationPlayer {
    /** Play the clip at [uri] from the start, replacing any clip still speaking. */
    fun play(uri: String)

    /** Stop the current clip, if any. */
    fun stop()
}

/** The platform [NarrationPlayer], released automatically when it leaves the composition. */
@Composable
expect fun rememberNarrationPlayer(): NarrationPlayer
