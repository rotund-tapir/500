// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Open Misère exposes the declarer's hand to the defenders (and only to them, and only during play).
 * This exercises the redaction rule in `view()` that the UI's exposed-hand row and PlayerView's
 * multiplayer contract both rely on.
 */
class OpenMisereExposureTest {
    private val rules = FiveHundredRules()

    private val declarerHand = listOf(Rank.FIVE of Suit.SPADES, Rank.SIX of Suit.HEARTS)

    private fun state(phase: Phase, bid: Bid): GameState {
        val seats = (0 until 4).map(::Seat)
        return GameState(
            rngSeed = 1L,
            handNumber = 1,
            dealer = Seat(3),
            phase = phase,
            hands = mapOf(
                Seat(0) to declarerHand,
                Seat(1) to listOf(Rank.SEVEN of Suit.CLUBS),
                Seat(2) to listOf(Rank.EIGHT of Suit.CLUBS),
                Seat(3) to listOf(Rank.NINE of Suit.CLUBS),
            ),
            kitty = emptyList(),
            bidding = BiddingState(toAct = Seat(0)),
            contract = Contract(Seat(0), bid),
            activeSeats = seats,
            leader = Seat(0),
            trickNumber = 0,
            tricksWon = seats.associateWith { 0 },
        )
    }

    @Test
    fun `during open-misere play every defender sees the declarer's hand`() {
        val s = state(Phase.PLAY, Bid.OpenMisere)
        for (seat in listOf(Seat(1), Seat(2), Seat(3))) {
            assertEquals(
                declarerHand,
                rules.view(s, seat).exposedDeclarerHand,
                "defender $seat should see the exposed hand",
            )
        }
    }

    @Test
    fun `the declarer never sees their own hand exposed`() {
        val s = state(Phase.PLAY, Bid.OpenMisere)
        assertNull(rules.view(s, Seat(0)).exposedDeclarerHand)
    }

    @Test
    fun `a plain misere exposes nothing`() {
        val s = state(Phase.PLAY, Bid.Misere)
        for (seat in (0 until 4).map(::Seat)) {
            assertNull(rules.view(s, seat).exposedDeclarerHand, "plain misère is not open")
        }
    }

    @Test
    fun `the hand is not exposed before play begins`() {
        val s = state(Phase.KITTY, Bid.OpenMisere)
        for (seat in (0 until 4).map(::Seat)) {
            assertNull(rules.view(s, seat).exposedDeclarerHand, "no exposure outside PLAY")
        }
    }
}
