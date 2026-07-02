// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rotundtapir.cardkit.monetization.Monetization
import io.github.rotundtapir.cardkit.ui.theme.CardkitTheme
import io.github.rotundtapir.fivehundred.ui.GameMode
import io.github.rotundtapir.fivehundred.ui.GameScreen
import io.github.rotundtapir.fivehundred.ui.HomeScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        /** Intent extra overriding the game seed — set by instrumentation tests for reproducibility. */
        const val EXTRA_SEED = "io.github.rotundtapir.fivehundred.SEED"

        /**
         * Intent extra (an [AnimationSpeed] name) overriding the persisted animation speed — set by
         * instrumentation tests to run without bot delays.
         */
        const val EXTRA_ANIMATION_SPEED = "io.github.rotundtapir.fivehundred.ANIMATION_SPEED"
    }

    private lateinit var monetization: Monetization

    private fun newGameSeed(): Long =
        if (intent?.hasExtra(EXTRA_SEED) == true) intent.getLongExtra(EXTRA_SEED, 0)
        else System.currentTimeMillis()

    /** The animation speed forced by the launching intent, or null to use the persisted setting. */
    private fun animationSpeedOverride(): AnimationSpeed? =
        intent?.getStringExtra(EXTRA_ANIMATION_SPEED)
            ?.let { name -> AnimationSpeed.entries.find { it.name == name } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monetization = MonetizationProvider.create(this)
        setContent {
            CardkitTheme {
                FiveHundredApp(monetization, this, ::newGameSeed, animationSpeedOverride())
            }
        }
    }

    override fun onDestroy() {
        monetization.dispose()
        super.onDestroy()
    }
}

@Composable
private fun FiveHundredApp(
    monetization: Monetization,
    activity: Activity,
    nextSeed: () -> Long,
    animationSpeedOverride: AnimationSpeed?,
) {
    val vm: GameViewModel = viewModel()
    // Saveable so an in-progress game survives activity recreation (rotation, theme change, …);
    // the game itself lives in the ViewModel.
    var inGame by rememberSaveable { mutableStateOf(false) }
    val view by vm.humanView.collectAsState()
    val botNames by vm.botNames.collectAsState()

    val settings = remember { SettingsRepository(activity.applicationContext) }
    val persistedSpeed by settings.animationSpeed.collectAsState(initial = AnimationSpeed.NORMAL)
    val animationSpeed = animationSpeedOverride ?: persistedSpeed
    val sortByDefault by settings.sortHandByDefault.collectAsState(initial = false)
    LaunchedEffect(animationSpeed) { vm.animationSpeed.value = animationSpeed }
    val scope = rememberCoroutineScope()
    val cycleAnimationSpeed: () -> Unit = {
        scope.launch { settings.setAnimationSpeed(animationSpeed.next()) }
    }
    val setSortByDefault: (Boolean) -> Unit = { value ->
        scope.launch { settings.setSortHandByDefault(value) }
    }
    val misereEnabled by settings.misereEnabled.collectAsState(initial = true)
    val noTrumpsEnabled by settings.noTrumpsEnabled.collectAsState(initial = true)
    val setMisereEnabled: (Boolean) -> Unit = { value ->
        scope.launch { settings.setMisereEnabled(value) }
    }
    val setNoTrumpsEnabled: (Boolean) -> Unit = { value ->
        scope.launch { settings.setNoTrumpsEnabled(value) }
    }
    // Stored by name so rememberSaveable needs no custom Saver.
    var modeName by rememberSaveable { mutableStateOf(GameMode.FOUR_PLAYER.name) }
    val mode = GameMode.valueOf(modeName)

    if (!inGame) {
        HomeScreen(
            monetization = monetization,
            activity = activity,
            onNewGame = {
                vm.newGame(nextSeed(), mode.players, misereEnabled, noTrumpsEnabled, mode.teams)
                inGame = true
            },
            animationSpeed = animationSpeed,
            onCycleAnimationSpeed = cycleAnimationSpeed,
            sortByDefault = sortByDefault,
            onSetSortByDefault = setSortByDefault,
            misereEnabled = misereEnabled,
            onSetMisereEnabled = setMisereEnabled,
            noTrumpsEnabled = noTrumpsEnabled,
            onSetNoTrumpsEnabled = setNoTrumpsEnabled,
            mode = mode,
            onModeChange = { modeName = it.name },
        )
    } else {
        val current = view
        if (current == null) {
            HomeScreen(
                monetization = monetization,
                activity = activity,
                onNewGame = { vm.newGame(nextSeed(), mode.players, misereEnabled, noTrumpsEnabled, mode.teams) },
                animationSpeed = animationSpeed,
                onCycleAnimationSpeed = cycleAnimationSpeed,
                sortByDefault = sortByDefault,
                onSetSortByDefault = setSortByDefault,
                misereEnabled = misereEnabled,
                onSetMisereEnabled = setMisereEnabled,
                noTrumpsEnabled = noTrumpsEnabled,
                onSetNoTrumpsEnabled = setNoTrumpsEnabled,
                mode = mode,
                onModeChange = { modeName = it.name },
            )
        } else {
            GameScreen(
                view = current,
                botNames = botNames,
                animationSpeed = animationSpeed,
                defaultSortHand = sortByDefault,
                monetization = monetization,
                activity = activity,
                onBid = vm::placeBid,
                onDiscard = vm::discard,
                onPlay = { card -> vm.playCard(card) },
                onExit = { inGame = false },
            )
        }
    }
}
