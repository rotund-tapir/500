// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberNarrationPlayer(): NarrationPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember { AndroidNarrationPlayer(context) }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    return player
}

/**
 * MediaPlayer-backed narration. Compose common resources are packed into the APK's assets, so
 * `Res.getUri` returns a `file:///android_asset/…` URI — opened via an AssetFileDescriptor
 * (MediaPlayer cannot read the pseudo-path itself; .mp3 is on AAPT's no-compress list, so the
 * descriptor window is valid). One short-lived MediaPlayer per clip keeps the state machine
 * trivial: no reuse, no reset bookkeeping.
 */
private class AndroidNarrationPlayer(private val context: Context) : NarrationPlayer {
    private var mediaPlayer: MediaPlayer? = null

    override fun play(uri: String) {
        stop()
        val player = MediaPlayer()
        mediaPlayer = player
        val result = runCatching {
            val assetPath = uri.substringAfter(ASSET_PREFIX, "")
            if (assetPath.isNotEmpty()) {
                context.assets.openFd(assetPath).use { fd ->
                    player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
            } else {
                player.setDataSource(uri)
            }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        }
        if (result.isFailure) stop() // a missing/undecodable clip must never crash the tutorial
    }

    override fun stop() {
        mediaPlayer?.let { runCatching { it.stop() }; it.release() }
        mediaPlayer = null
    }

    private companion object {
        const val ASSET_PREFIX = "file:///android_asset/"
    }
}
