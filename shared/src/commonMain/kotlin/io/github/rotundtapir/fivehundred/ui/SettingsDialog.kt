// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.LocalAppConfig

private const val CARD_ART_URL = "https://code.google.com/archive/p/vector-playing-cards/"

/**
 * The settings dialog, opened from the cog on the home screen or in a game. With [inGame] set, the
 * house-rule switches (which only apply to new games) are disabled.
 */
@Composable
fun SettingsDialog(
    animationSpeed: AnimationSpeed,
    onCycleAnimationSpeed: () -> Unit,
    sortByDefault: Boolean,
    onSetSortByDefault: (Boolean) -> Unit,
    holdTricks: Boolean,
    onSetHoldTricks: (Boolean) -> Unit,
    soundVolume: Float,
    onSetSoundVolume: (Float) -> Unit,
    misereEnabled: Boolean,
    onSetMisereEnabled: (Boolean) -> Unit,
    noTrumpsEnabled: Boolean,
    onSetNoTrumpsEnabled: (Boolean) -> Unit,
    inGame: Boolean,
    monetization: Monetization,
    onDismiss: () -> Unit,
) {
    var showAcknowledgments by remember { mutableStateOf(false) }
    if (showAcknowledgments) {
        AcknowledgmentsDialog(onDismiss = { showAcknowledgments = false })
        return
    }
    var showRules by remember { mutableStateOf(false) }
    if (showRules) {
        RulesDialog(onDismiss = { showRules = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Animations")
                    OutlinedButton(
                        onClick = onCycleAnimationSpeed,
                        modifier = Modifier.testTag("animationSpeed"),
                    ) { Text(animationSpeed.label) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sort hand by default")
                    Switch(
                        checked = sortByDefault,
                        onCheckedChange = onSetSortByDefault,
                        modifier = Modifier.testTag("sortDefault"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Hold completed tricks")
                    Switch(
                        checked = holdTricks,
                        onCheckedChange = onSetHoldTricks,
                        modifier = Modifier.testTag("holdTricks"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sound volume")
                    Slider(
                        value = soundVolume,
                        onValueChange = onSetSoundVolume,
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f).testTag("volumeSlider"),
                    )
                }

                HorizontalDivider()

                Text("House rules (apply to new games)", style = MaterialTheme.typography.labelMedium)
                // In-game these can't take effect until the next game — shown but disabled.
                val houseRuleColor =
                    if (inGame) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Misère bids", color = houseRuleColor)
                    Switch(
                        checked = misereEnabled,
                        onCheckedChange = onSetMisereEnabled,
                        enabled = !inGame,
                        modifier = Modifier.testTag("misereEnabled"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("No-trump bids", color = houseRuleColor)
                    Switch(
                        checked = noTrumpsEnabled,
                        onCheckedChange = onSetNoTrumpsEnabled,
                        enabled = !inGame,
                        modifier = Modifier.testTag("noTrumpsEnabled"),
                    )
                }

                HorizontalDivider()

                val adsRemoved by monetization.adsRemoved.collectAsState()
                OutlinedButton(
                    onClick = { monetization.launchRemoveAdsOrDonate() },
                    modifier = Modifier.fillMaxWidth().testTag("supportButton"),
                ) {
                    Text(
                        when {
                            !monetization.offersRemoveAds -> "Support development"
                            adsRemoved -> "Ads removed — thank you!"
                            else -> "Remove ads"
                        }
                    )
                }
                // EEA/UK users may revisit their ad-consent choices (a Google UMP requirement).
                // Never shown in FOSS builds, where privacyOptionsRequired is always false.
                val privacyOptionsRequired by monetization.privacyOptionsRequired.collectAsState()
                if (privacyOptionsRequired) {
                    OutlinedButton(
                        onClick = { monetization.showPrivacyOptionsForm() },
                        modifier = Modifier.fillMaxWidth().testTag("privacyOptions"),
                    ) { Text("Privacy options") }
                }
                OutlinedButton(
                    onClick = { showRules = true },
                    modifier = Modifier.fillMaxWidth().testTag("helpButton"),
                ) { Text("Help — rules of 500") }
                val uriHandler = LocalUriHandler.current
                val feedbackUri = LocalAppConfig.current.feedbackUri
                OutlinedButton(
                    onClick = {
                        // FOSS/web: the GitHub issue tracker; Play: a mailto to the developer.
                        runCatching { uriHandler.openUri(feedbackUri) }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("feedbackButton"),
                ) { Text("Submit feedback") }
                OutlinedButton(
                    onClick = { showAcknowledgments = true },
                    modifier = Modifier.fillMaxWidth().testTag("acknowledgments"),
                ) { Text("Acknowledgments") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/** Credits for the bundled artwork. */
@Composable
private fun AcknowledgmentsDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Acknowledgments") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The playing card faces are “Vector Playing Cards” by Byron Knoll, " +
                        "released into the public domain.",
                )
                Text(
                    "The 11, 12 and 13 card faces and the card back were created for this app " +
                        "in the same style and are dedicated to the public domain (CC0).",
                )
                Text(
                    "Sound effects from Kenney's Casino Audio pack (kenney.nl), " +
                        "public domain (CC0).",
                )
                TextButton(onClick = { uriHandler.openUri(CARD_ART_URL) }) {
                    Text("View Byron Knoll's card set")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("acknowledgmentsClose")) {
                Text("Close")
            }
        },
    )
}
