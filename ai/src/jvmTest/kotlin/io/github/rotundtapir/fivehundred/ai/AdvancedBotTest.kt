// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.StrategyPlayer
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Contract
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class AdvancedBotTest {

    /** Fixed-iteration deterministic mode: a decision is a pure function of (view, tracker, Random). */
    private fun fixedConfig(worlds: Int) = SearchConfig(
        maxDeterminizations = worlds,
        minDeterminizations = worlds / 2,
        batchSize = 4,
        timeBudgetEnabled = false,
    )

    private fun advancedPlayer(rules: FiveHundredRules, config: SearchConfig, seed: Long): Player<PlayerView, Action> {
        val bot = AdvancedBot(rules, config = config)
        val random = Random(seed)
        return Player { view -> bot.decide(view, random) }
    }

    private fun capped(rules: FiveHundredRules, handCap: Int) =
        object : GameRules<GameState, Action, PlayerView> by rules {
            override fun isTerminal(state: GameState): Boolean =
                rules.isTerminal(state) || state.handNumber > handCap
        }

    private suspend fun playAllAdvanced(playerCount: Int, seed: Long, worlds: Int, handCap: Int): GameState {
        val rules = FiveHundredRules(playerCount = playerCount)
        val players = (0 until playerCount).associate {
            Seat(it) to advancedPlayer(rules, fixedConfig(worlds), seed + it)
        }
        // If any bot ever returned an illegal move, rules.apply would throw and fail the test.
        return GameDriver(capped(rules, handCap), players).play(rules.newGame(seed))
    }

    @Test
    fun `four advanced bots play a full match making only legal moves`() = runTest(timeout = 5.minutes) {
        val terminal = playAllAdvanced(playerCount = 4, seed = 2024L, worlds = 16, handCap = 30)
        assertNotNull(terminal.lastHandResult, "at least one contract should have been played out")
    }

    @Test
    fun `advanced bots play 2- and 6-player matches making only legal moves`() = runTest(timeout = 5.minutes) {
        for (playerCount in listOf(2, 6)) {
            val terminal = playAllAdvanced(playerCount = playerCount, seed = 2026L, worlds = 8, handCap = 20)
            assertNotNull(terminal.lastHandResult, "at $playerCount players at least one contract should complete")
        }
    }

    @Test
    fun `a fixed-iteration advanced match is deterministic for a given seed`() = runTest(timeout = 5.minutes) {
        suspend fun run() = playAllAdvanced(playerCount = 4, seed = 77L, worlds = 12, handCap = 12)
        val first = run()
        val second = run()
        assertEquals(first.scores, second.scores)
        assertEquals(first.handNumber, second.handNumber)
    }

    @Test
    fun `advanced bots beat heuristic bots over seeded matches`() = runTest(timeout = 10.minutes) {
        val heuristic = FiveHundredBot()
        var advancedWins = 0
        val matches = 12
        for (seed in 1L..matches) {
            val rules = FiveHundredRules()
            val players = (0 until 4).associate { i ->
                Seat(i) to if (i % 2 == 0) {
                    advancedPlayer(rules, fixedConfig(24), seed + i) // team 0: seats 0 and 2
                } else {
                    StrategyPlayer(heuristic, Random(seed + i))
                }
            }
            val terminal = GameDriver(capped(rules, 30), players).play(rules.newGame(seed))
            if ((terminal.scores[0] ?: 0) > (terminal.scores[1] ?: 0)) advancedWins++
        }
        println("advanced team won $advancedWins of $matches seeded matches")
        // Deterministic given the fixed seeds/config — this is a strength-regression gate, not a
        // flaky statistical test. Update deliberately if search or heuristics change.
        assertTrue(
            advancedWins >= (matches * 6) / 10,
            "advanced team won only $advancedWins of $matches matches",
        )
    }

    @Test
    fun `wall-clock budget bounds a decision`() = runTest(timeout = 1.minutes) {
        val rules = FiveHundredRules()
        val config = SearchConfig(
            bidBudget = 200.milliseconds,
            maxDeterminizations = Int.MAX_VALUE,
            minDeterminizations = 1, // floor satisfied immediately: the clock is in charge...
            batchSize = Int.MAX_VALUE, // ...and racing never fires (worlds % batch is never 0)
        )
        val bot = AdvancedBot(rules, config = config)
        val state = rules.newGame(seed = 5L)
        val opener = rules.currentActor(state)!!
        val start = TimeSource.Monotonic.markNow()
        val action = bot.decide(rules.view(state, opener), Random(1))
        val elapsed = start.elapsedNow()
        assertTrue(action is Action.PlaceBid)
        // Generous CI margin; catches a runaway search that ignores its deadline.
        assertTrue(elapsed < 2.seconds, "bidding took $elapsed against a 200ms budget")
    }

    @Test
    fun `bidding samples the floor even when the budget has already expired`() = runTest(timeout = 1.minutes) {
        val rules = FiveHundredRules()
        val state = rules.newGame(seed = 5L)
        val opener = rules.currentActor(state)!!
        val view = rules.view(state, opener)

        // A zero budget with a 16-world floor must behave exactly like a fixed 16-world search:
        // same worlds, same racing points, same Random stream => the identical action.
        val floored = SearchConfig(
            bidBudget = Duration.ZERO,
            maxDeterminizations = Int.MAX_VALUE,
            minDeterminizations = 16,
        )
        val fixed = floored.copy(maxDeterminizations = 16, timeBudgetEnabled = false)
        val flooredAction = AdvancedBot(rules, config = floored).decide(view, Random(3))
        val fixedAction = AdvancedBot(rules, config = fixed).decide(view, Random(3))
        assertEquals(fixedAction, flooredAction)
    }

    @Test
    fun `a forced move returns without sampling`() = runTest(timeout = 1.minutes) {
        val rules = FiveHundredRules()
        // Following a heart lead holding exactly one heart: a single legal play. With sampling
        // uncapped, only the single-arm early exit can return this fast.
        val config = SearchConfig(maxDeterminizations = Int.MAX_VALUE, timeBudgetEnabled = false)
        val bot = AdvancedBot(rules, config = config)
        val onlyHeart = Rank.FIVE of Suit.HEARTS
        val hand = listOf(
            onlyHeart,
            Rank.ACE of Suit.SPADES, Rank.KING of Suit.SPADES, Rank.QUEEN of Suit.SPADES,
            Rank.ACE of Suit.CLUBS, Rank.KING of Suit.CLUBS,
            Rank.ACE of Suit.DIAMONDS, Rank.KING of Suit.DIAMONDS, Rank.QUEEN of Suit.DIAMONDS,
        )
        val contract = Contract(Seat(1), Bid.Named(7, Trump.SPADES))
        val view = PlayerView(
            seat = Seat(0),
            phase = Phase.PLAY,
            playerCount = 4,
            teamCount = 2,
            handNumber = 1,
            hand = hand,
            handSizes = mapOf(Seat(0) to 9, Seat(1) to 9, Seat(2) to 10, Seat(3) to 10),
            dealer = Seat(3),
            scores = mapOf(0 to 0, 1 to 0),
            toAct = Seat(0),
            biddingHistory = emptyList(),
            highBid = contract.bid,
            highBidder = Seat(1),
            legalBids = emptyList(),
            contract = contract,
            trump = contract.trump,
            leader = Seat(3),
            currentTrick = listOf(TrickPlay(Seat(3), Rank.ACE of Suit.HEARTS)),
            ledSuit = Suit.HEARTS,
            lastTrick = null,
            tricksWon = mapOf(Seat(0) to 0, Seat(1) to 0, Seat(2) to 0, Seat(3) to 0),
            trickNumber = 0,
            legalPlays = listOf(onlyHeart),
            mustDiscard = 0,
            exposedDeclarerHand = null,
            activeSeats = listOf(Seat(0), Seat(1), Seat(2), Seat(3)),
            lastHandResult = null,
            winner = null,
        )
        val action = bot.decide(view, Random(1))
        assertEquals(Action.PlayCard(onlyHeart), action)
    }

    @Test
    fun `search failure falls back to the heuristic answer`() = runTest(timeout = 1.minutes) {
        // A 4-player AdvancedBot fed a 6-player view: the determinizer's 43-card deck cannot cover
        // six 10-card hands, so the search throws internally and the heuristic must answer instead.
        val sixHanded = FiveHundredRules(playerCount = 6)
        val state = sixHanded.newGame(seed = 3L)
        val opener = sixHanded.currentActor(state)!!
        val view = sixHanded.view(state, opener)

        val mismatched = AdvancedBot(FiveHundredRules(playerCount = 4), config = fixedConfig(8))
        val action = mismatched.decide(view, Random(1))
        assertEquals(FiveHundredBot().decide(view, Random(1)), action)
    }

    @Test
    fun `advanced bot player answers through the Player seam`() = runTest(timeout = 1.minutes) {
        val rules = FiveHundredRules()
        val player = AdvancedBotPlayer(AdvancedBot(rules, config = fixedConfig(8)), Random(9))
        val state = rules.newGame(seed = 9L)
        val opener = rules.currentActor(state)!!
        val action = player.decide(rules.view(state, opener))
        val legal = rules.view(state, opener).legalBids
        assertTrue(action is Action.PlaceBid && action.bid in legal)
    }
}
