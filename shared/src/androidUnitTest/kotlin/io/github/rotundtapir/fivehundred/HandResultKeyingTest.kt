// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Contract
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.HandResult
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the hand-result deadlock: HandResultDialog and the SCORE sound must key on
 * the scored-hand COUNT, never on HandResult value equality. This pins the two facts that make the
 * count key correct and the value key unsound.
 */
class HandResultKeyingTest {

    @Test
    fun `two structurally identical hand results are value-equal`() {
        // The exact hazard: HandResult carries no hand number, so two consecutive hands with the
        // same declarer, bid, trick count and deltas produce == results. A remember(lastHandResult)
        // key would not reset between them, and the second dialog would never show — deadlocking
        // every acknowledgement gate that waits on its onDismissed.
        val contract = Contract(Seat(1), Bid.Named(6, Trump.SPADES))
        val a = HandResult(contract, declarerTricks = 6, made = true, teamDeltas = mapOf(0 to 60, 1 to 40))
        val b = HandResult(contract, declarerTricks = 6, made = true, teamDeltas = mapOf(0 to 60, 1 to 40))
        assertEquals(a, b, "identical hand results compare equal — so value cannot identify a hand")
    }

    @Test
    fun `handResults size increases by exactly one per scored hand`() {
        // The count key's soundness: across a real match, handResults.size is strictly increasing
        // at each scored hand (and unchanged within a hand and on passed-out redeals), so it is a
        // unique, monotonic key even when consecutive results are value-equal.
        val rules = FiveHundredRules(playerCount = 4)
        val bot = FiveHundredBot()
        val randoms = (0 until 4).associate { Seat(it) to Random(2024L + it) }
        var state = rules.newGame(2024L)

        var lastSize = 0
        var guard = 0
        while (state.phase != Phase.COMPLETE && guard++ < 4000) {
            val seat = rules.currentActor(state) ?: break
            state = rules.apply(state, seat, bot.decide(rules.view(state, seat), randoms.getValue(seat)))
            val size = state.handResults.size
            assertTrue(size == lastSize || size == lastSize + 1, "handResults grew by at most one")
            lastSize = size
        }
        assertTrue(lastSize >= 1, "the match should have scored at least one hand")
        assertEquals(state.handResults.size, lastSize)
    }
}
