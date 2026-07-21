// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GameState] must survive a JSON round trip losslessly — the online server persists room snapshots
 * of it to disk and restores them after a restart. Exercises every phase a snapshot can capture,
 * including the value-class map keys ([io.github.rotundtapir.cardkit.core.Seat]) and the
 * `Pair<Seat, Bid>` bidding history that only fail at encode time, not compile time.
 */
class GameStateSerializationTest {

    private val json = Json

    private fun roundTrip(state: GameState): GameState =
        json.decodeFromString<GameState>(json.encodeToString(GameState.serializer(), state))

    @Test
    fun `fresh deal round-trips losslessly`() {
        val rules = FiveHundredRules(playerCount = 4, teamCount = 2)
        val state = rules.newGame(seed = 42)
        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `states in every phase of a full match round-trip losslessly`() {
        val rules = FiveHundredRules(playerCount = 4, teamCount = 2)
        var state = rules.newGame(seed = 42)
        val phasesSeen = mutableSetOf<Phase>()
        var steps = 0
        while (!rules.isTerminal(state) && steps < MAX_STEPS) {
            phasesSeen += state.phase
            assertEquals(state, roundTrip(state), "round trip diverged at step $steps (${state.phase})")
            val seat = rules.currentActor(state) ?: break
            // KITTY has no enumerated legal actions (the discard is a constructed action) — discard
            // the first three cards in hand. In BIDDING prefer a real bid over Pass so the auction
            // settles and the match progresses (all-pass would redeal forever).
            val action = if (state.phase == Phase.KITTY) {
                Action.ExchangeKitty(state.hands.getValue(seat).take(KITTY_SIZE))
            } else {
                val actions = rules.legalActions(state, seat)
                actions.firstOrNull { (it as? Action.PlaceBid)?.bid != Bid.Pass } ?: actions.first()
            }
            state = rules.apply(state, seat, action)
            steps++
        }
        assertEquals(state, roundTrip(state))
        // "First legal action" always bids then plays, so a real match reaches every phase.
        assertTrue(
            phasesSeen.containsAll(setOf(Phase.BIDDING, Phase.KITTY, Phase.PLAY)),
            "expected a full match, saw only $phasesSeen",
        )
    }

    private companion object {
        const val MAX_STEPS = 10_000
    }
}
