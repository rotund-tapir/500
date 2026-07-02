// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Seat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * House-rule toggles (disabling misère / no-trump bids) and the traditional misère gate: Misère and
 * Open Misère may only be called once the auction has reached seven tricks.
 */
class HouseRulesTest {

    private fun openerBids(rules: FiveHundredRules): List<Bid> {
        val state = rules.newGame(seed = 5)
        return rules.view(state, rules.currentActor(state)!!).legalBids
    }

    /** Opens the auction with [opening], returning the next bidder's legal bids and the state. */
    private fun bidsAfter(rules: FiveHundredRules, vararg openings: Bid): Pair<List<Bid>, GameState> {
        var state = rules.newGame(seed = 5)
        for (bid in openings) {
            state = rules.apply(state, rules.currentActor(state)!!, Action.PlaceBid(bid))
        }
        return rules.view(state, rules.currentActor(state)!!).legalBids to state
    }

    // --- The misère-after-seven gate --------------------------------------------------------------

    @Test
    fun `misere is not offered before the bidding reaches seven`() {
        val rules = FiveHundredRules()
        assertFalse(Bid.Misere in openerBids(rules))
        assertFalse(Bid.OpenMisere in openerBids(rules))

        val (afterSixHearts, _) = bidsAfter(rules, Bid.Named(6, Trump.HEARTS))
        assertFalse(Bid.Misere in afterSixHearts)
        assertFalse(Bid.OpenMisere in afterSixHearts)
        assertTrue(Bid.Named(7, Trump.SPADES) in afterSixHearts)
    }

    @Test
    fun `misere unlocks once a seven bid has been made`() {
        val (bids, _) = bidsAfter(FiveHundredRules(), Bid.Named(7, Trump.SPADES))
        assertTrue(Bid.Misere in bids)
        assertTrue(Bid.OpenMisere in bids)
    }

    @Test
    fun `misere cannot open the auction even when submitted directly`() {
        val rules = FiveHundredRules()
        val state = rules.newGame(seed = 5)
        assertThrows<IllegalArgumentException> {
            rules.apply(state, rules.currentActor(state)!!, Action.PlaceBid(Bid.Misere))
        }
        assertThrows<IllegalArgumentException> {
            rules.apply(state, rules.currentActor(state)!!, Action.PlaceBid(Bid.OpenMisere))
        }
    }

    @Test
    fun `open misere may follow a misere`() {
        val (bids, _) = bidsAfter(FiveHundredRules(), Bid.Named(7, Trump.SPADES), Bid.Misere)
        assertFalse(Bid.Misere in bids) // does not outrank itself
        assertTrue(Bid.OpenMisere in bids)
    }

    @Test
    fun `an eight bid above misere still leaves open misere callable`() {
        val (bids, _) = bidsAfter(FiveHundredRules(), Bid.Named(7, Trump.SPADES), Bid.Named(8, Trump.CLUBS))
        assertFalse(Bid.Misere in bids) // 8♣ outranks Misère
        assertTrue(Bid.OpenMisere in bids)
    }

    // --- House-rule toggles ------------------------------------------------------------------------

    @Test
    fun `misere disabled removes both misere bids even past the gate`() {
        val (bids, _) = bidsAfter(FiveHundredRules(misereEnabled = false), Bid.Named(7, Trump.SPADES))
        assertFalse(Bid.Misere in bids)
        assertFalse(Bid.OpenMisere in bids)
        assertTrue(Bid.Named(10, Trump.NO_TRUMP) in bids)
        assertTrue(Bid.Named(8, Trump.SPADES) in bids)
        assertTrue(Bid.Pass in bids)
    }

    @Test
    fun `no-trumps disabled removes every NT level but keeps misere past the gate`() {
        val (bids, _) = bidsAfter(FiveHundredRules(noTrumpsEnabled = false), Bid.Named(7, Trump.SPADES))
        for (level in 6..10) assertFalse(Bid.Named(level, Trump.NO_TRUMP) in bids)
        assertTrue(Bid.Misere in bids)
        assertTrue(Bid.OpenMisere in bids)
        assertTrue(Bid.Named(10, Trump.HEARTS) in bids)
    }

    @Test
    fun `both disabled leaves only suited contracts for the opener`() {
        val bids = openerBids(FiveHundredRules(misereEnabled = false, noTrumpsEnabled = false))
        assertTrue(bids.filter { it != Bid.Pass }.all { it is Bid.Named && it.trump != Trump.NO_TRUMP })
        assertTrue(bids.size == 1 + 20, "Pass + 5 levels x 4 suits, got ${bids.size}")
    }

    @Test
    fun `a disabled bid is rejected even past the gate`() {
        val rules = FiveHundredRules(misereEnabled = false)
        var state = rules.newGame(seed = 5)
        state = rules.apply(state, rules.currentActor(state)!!, Action.PlaceBid(Bid.Named(7, Trump.SPADES)))
        assertThrows<IllegalArgumentException> {
            rules.apply(state, rules.currentActor(state)!!, Action.PlaceBid(Bid.Misere))
        }
    }

    @Test
    fun `defaults leave the full ladder biddable once gated`() {
        val (bids, _) = bidsAfter(FiveHundredRules(), Bid.Named(7, Trump.SPADES))
        assertTrue(Bid.Misere in bids)
        assertTrue(Bid.OpenMisere in bids)
        assertTrue(Bid.Named(7, Trump.NO_TRUMP) in bids)
    }
}
