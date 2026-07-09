// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Contract
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bot's normal (non-Misère) play heuristics: lead strong, don't overtake a winning partner,
 * win as cheaply as possible when it can, dump the lowest when it can't. Seat 0 is on team 0 with
 * its partner at Seat 2; Seats 1 and 3 are opponents (2 teams of 2).
 */
class FiveHundredBotPlayTest {
    private val bot = FiveHundredBot()
    private val trump = Trump.SPADES

    /** A PLAY-phase view for seat 0 with the given hand, trick-so-far and legal plays. */
    private fun playView(
        hand: List<Card>,
        trick: List<TrickPlay>,
        legal: List<Card> = hand,
    ): PlayerView {
        val eval = TrickEvaluator(trump)
        return PlayerView(
            seat = Seat(0),
            phase = Phase.PLAY,
            playerCount = 4,
            teamCount = 2,
            handNumber = 1,
            hand = hand,
            handSizes = emptyMap(),
            dealer = Seat(3),
            scores = emptyMap(),
            toAct = Seat(0),
            biddingHistory = emptyList(),
            highBid = Bid.Named(7, trump),
            highBidder = Seat(1),
            legalBids = emptyList(),
            contract = Contract(declarer = Seat(1), bid = Bid.Named(7, trump)),
            trump = trump,
            leader = trick.firstOrNull()?.seat ?: Seat(0),
            currentTrick = trick,
            ledSuit = trick.firstOrNull()?.let { eval.ledSuitOf(it) },
            lastTrick = null,
            tricksWon = emptyMap(),
            trickNumber = 1,
            legalPlays = legal,
            mustDiscard = 0,
            exposedDeclarerHand = null,
            activeSeats = listOf(Seat(0), Seat(1), Seat(2), Seat(3)),
            lastHandResult = null,
            winner = null,
        )
    }

    @Test
    fun `leading, it plays its strongest card`() {
        // A trump outranks side cards in the bot's keep/dump ordering, so leading it plays the K♠.
        val hand = listOf(Rank.KING of Suit.SPADES, Rank.NINE of Suit.HEARTS, Rank.FIVE of Suit.CLUBS)
        assertEquals(Rank.KING of Suit.SPADES, bot.choosePlay(playView(hand, trick = emptyList())))
    }

    @Test
    fun `it lets a winning teammate be, dumping its lowest`() {
        // Partner (Seat 2) leads the A♥ and is winning; seat 0 is void in hearts and could trump,
        // but must not steal the partner's trick — it dumps its lowest card, the 4♣.
        val hand = listOf(Rank.KING of Suit.SPADES, Rank.NINE of Suit.DIAMONDS, Rank.FOUR of Suit.CLUBS)
        val trick = listOf(TrickPlay(Seat(2), Rank.ACE of Suit.HEARTS))
        assertEquals(Rank.FOUR of Suit.CLUBS, bot.choosePlay(playView(hand, trick, legal = hand)))
    }

    @Test
    fun `it wins as cheaply as possible when it can beat the trick`() {
        // An opponent (Seat 1) leads the Q♥. Seat 0 can win with K♥ or A♥ — it takes the cheaper K♥.
        val hand = listOf(Rank.KING of Suit.HEARTS, Rank.ACE of Suit.HEARTS, Rank.FIVE of Suit.CLUBS)
        val trick = listOf(TrickPlay(Seat(1), Rank.QUEEN of Suit.HEARTS))
        // Following hearts is forced, so the two hearts are the legal plays.
        val legal = listOf(Rank.KING of Suit.HEARTS, Rank.ACE of Suit.HEARTS)
        assertEquals(Rank.KING of Suit.HEARTS, bot.choosePlay(playView(hand, trick, legal)))
    }

    @Test
    fun `it dumps its lowest when it cannot win`() {
        // Opponent (Seat 1) leads the A♥ — unbeatable by seat 0's low hearts; it sheds the 6♥.
        val hand = listOf(Rank.NINE of Suit.HEARTS, Rank.SIX of Suit.HEARTS, Rank.KING of Suit.SPADES)
        val trick = listOf(TrickPlay(Seat(1), Rank.ACE of Suit.HEARTS))
        val legal = listOf(Rank.NINE of Suit.HEARTS, Rank.SIX of Suit.HEARTS)
        assertEquals(Rank.SIX of Suit.HEARTS, bot.choosePlay(playView(hand, trick, legal)))
    }
}
