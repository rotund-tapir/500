// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.ChannelPlayer
import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.StrategyPlayer
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.PLAYERS
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Drives a game of 500 for the human at seat 0 against three [FiveHundredBot] opponents.
 *
 * The engine's [GameDriver] runs in [viewModelScope]; every state transition is pushed to [humanView]
 * (the redacted, seat-0 projection) so the UI can render. Human decisions are fed back through a
 * [ChannelPlayer] — the same seam a remote opponent would use.
 */
class GameViewModel : ViewModel() {
    private val rules = FiveHundredRules()
    private val bot = FiveHundredBot()
    private val humanSeat = Seat(0)
    private val human = ChannelPlayer<PlayerView, Action>()

    private val state = MutableStateFlow<GameState?>(null)

    /** The human's view of the game, or null before a game starts. */
    val humanView: StateFlow<PlayerView?> = state
        .map { snapshot -> snapshot?.let { rules.view(it, humanSeat) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var gameJob: Job? = null

    fun newGame(seed: Long) {
        gameJob?.cancel()
        state.value = null
        val players: Map<Seat, Player<PlayerView, Action>> = buildMap {
            put(humanSeat, human)
            for (i in 1 until PLAYERS) put(Seat(i), StrategyPlayer(bot, Random(seed + i)))
        }
        gameJob = viewModelScope.launch {
            GameDriver(rules, players).play(rules.newGame(seed)) { snapshot -> state.value = snapshot }
        }
    }

    fun placeBid(bid: Bid) = submit(Action.PlaceBid(bid))
    fun discard(cards: List<Card>) = submit(Action.ExchangeKitty(cards))
    fun playCard(card: Card, nominate: Suit? = null) = submit(Action.PlayCard(card, nominate))

    private fun submit(action: Action) {
        viewModelScope.launch { human.submit(action) }
    }
}
