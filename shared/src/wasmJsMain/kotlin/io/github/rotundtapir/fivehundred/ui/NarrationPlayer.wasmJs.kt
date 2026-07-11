// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** The browser's global HTMLAudioElement constructor; enough surface for one-shot clips. */
private external class Audio(src: String) : JsAny {
    fun pause()
}

/**
 * Kick off playback, swallowing the rejection `play()` returns when the browser blocks autoplay —
 * an unhandled rejection would land in the console (and fail the error-clean e2e assertions). In
 * practice narration always follows a click, which satisfies the gesture policy.
 */
@Suppress("UnusedParameter") // referenced inside the js() snippet, invisible to static analysis
private fun playSafely(audio: Audio): Unit = js("{ audio.play().catch(function () {}); }")

@Composable
actual fun rememberNarrationPlayer(): NarrationPlayer {
    val player = remember { WasmNarrationPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    return player
}

private class WasmNarrationPlayer : NarrationPlayer {
    private var audio: Audio? = null

    override fun play(uri: String) {
        stop()
        audio = Audio(uri).also { playSafely(it) }
    }

    override fun stop() {
        audio?.pause()
        audio = null
    }
}
