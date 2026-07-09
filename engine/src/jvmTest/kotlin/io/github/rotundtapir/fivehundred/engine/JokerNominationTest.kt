// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Joker-nomination glue through the reducer at no-trump: leading the Joker names the led suit
 * (or leaves play unconstrained), which the reducer records and follow-suit legality then enforces.
 */
class JokerNominationTest {
    private val rules = FiveHundredRules()

    /** A PLAY-phase no-trump state where [leaderHand]/[followerHand] are dealt and Seat 0 leads. */
    private fun noTrumpPlay(leaderHand: List<Card>, followerHand: List<Card>): GameState {
        val seats = (0 until 4).map(::Seat)
        return GameState(
            rngSeed = 1L,
            handNumber = 1,
            dealer = Seat(3),
            phase = Phase.PLAY,
            hands = mapOf(
                Seat(0) to leaderHand,
                Seat(1) to followerHand,
                Seat(2) to listOf(Rank.FIVE of Suit.CLUBS),
                Seat(3) to listOf(Rank.SIX of Suit.CLUBS),
            ),
            kitty = emptyList(),
            bidding = BiddingState(toAct = Seat(0)),
            contract = Contract(Seat(0), Bid.Named(6, Trump.NO_TRUMP)),
            activeSeats = seats,
            leader = Seat(0),
            currentTrick = emptyList(),
            ledSuit = null,
            trickNumber = 0,
            tricksWon = seats.associateWith { 0 },
        )
    }

    @Test
    fun `leading the Joker at no-trump names the led suit and binds followers to it`() {
        val state = noTrumpPlay(
            leaderHand = listOf(Joker, Rank.ACE of Suit.SPADES),
            followerHand = listOf(Rank.KING of Suit.HEARTS, Rank.ACE of Suit.SPADES),
        )
        val next = rules.apply(state, Seat(0), Action.PlayCard(Joker, nominate = Suit.HEARTS))

        assertEquals(Suit.HEARTS, next.ledSuit, "the nominated suit becomes the led suit")
        assertEquals(Seat(1), rules.currentActor(next))
        // Seat 1 holds a heart, so hearts is the only legal follow.
        assertEquals(
            listOf<Card>(Rank.KING of Suit.HEARTS),
            rules.view(next, Seat(1)).legalPlays,
            "a follower holding the nominated suit must follow it",
        )
    }

    @Test
    fun `leading the Joker with no nomination leaves following unconstrained`() {
        // ledSuitOf returns null for a no-trump Joker led without a nomination; legalFollows(null)
        // then leaves the whole hand playable.
        val followerHand = listOf(Rank.KING of Suit.HEARTS, Rank.ACE of Suit.SPADES)
        val state = noTrumpPlay(
            leaderHand = listOf(Joker, Rank.ACE of Suit.SPADES),
            followerHand = followerHand,
        )
        val next = rules.apply(state, Seat(0), Action.PlayCard(Joker, nominate = null))

        assertEquals(null, next.ledSuit)
        assertTrue(
            rules.view(next, Seat(1)).legalPlays.containsAll(followerHand),
            "with no nomination the follower may play anything",
        )
    }
}
