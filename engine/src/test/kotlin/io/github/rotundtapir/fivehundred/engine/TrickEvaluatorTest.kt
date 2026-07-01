// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrickEvaluatorTest {
    private val spades = TrickEvaluator(Trump.SPADES)
    private val noTrump = TrickEvaluator(Trump.NO_TRUMP)

    private fun play(seat: Int, card: io.github.rotundtapir.cardkit.core.Card, nominated: Suit? = null) =
        TrickPlay(Seat(seat), card, nominated)

    @Test
    fun `bower and joker ordering in a suit contract`() {
        val jokerS = spades.strength(Joker, Suit.SPADES)
        val right = spades.strength(Rank.JACK of Suit.SPADES, Suit.SPADES)   // right bower
        val left = spades.strength(Rank.JACK of Suit.CLUBS, Suit.SPADES)     // left bower
        val aceTrump = spades.strength(Rank.ACE of Suit.SPADES, Suit.SPADES)
        assertTrue(jokerS > right && right > left && left > aceTrump)
    }

    @Test
    fun `left bower counts as the trump suit`() {
        val leftBower = Rank.JACK of Suit.CLUBS // with spades trump
        assertTrue(spades.isTrump(leftBower))
        assertTrue(spades.isLeftBower(leftBower))
        assertEquals(Suit.SPADES, spades.effectiveSuit(leftBower))
        // The Jack of clubs is NOT a club anymore for following purposes.
        assertFalse(spades.effectiveSuit(leftBower) == Suit.CLUBS)
    }

    @Test
    fun `any trump beats a high card of the led non-trump suit`() {
        // Hearts led, someone trumps with the 5 of spades.
        val trick = listOf(
            play(0, Rank.ACE of Suit.HEARTS),
            play(1, Rank.FIVE of Suit.SPADES),
            play(2, Rank.KING of Suit.HEARTS),
            play(3, Rank.QUEEN of Suit.HEARTS),
        )
        assertEquals(Seat(1), spades.winner(trick))
    }

    @Test
    fun `left bower beats the ace of trumps in a trick`() {
        val trick = listOf(
            play(0, Rank.ACE of Suit.SPADES),
            play(1, Rank.JACK of Suit.CLUBS), // left bower
        )
        assertEquals(Seat(1), spades.winner(trick))
    }

    @Test
    fun `highest of led suit wins when no trumps are played`() {
        val trick = listOf(
            play(0, Rank.NINE of Suit.HEARTS),
            play(1, Rank.ACE of Suit.HEARTS),
            play(2, Rank.SEVEN of Suit.DIAMONDS), // off-suit, cannot win
            play(3, Rank.KING of Suit.HEARTS),
        )
        assertEquals(Seat(1), spades.winner(trick))
    }

    @Test
    fun `must follow the led suit, and the left bower satisfies a trump lead`() {
        // Spades (trump) led. Hand holds the left bower (J clubs) + hearts. Must play the "spade".
        val hand = listOf(Rank.JACK of Suit.CLUBS, Rank.ACE of Suit.HEARTS, Rank.KING of Suit.HEARTS)
        val legal = spades.legalFollows(hand, ledSuit = Suit.SPADES)
        assertEquals(listOf(Rank.JACK of Suit.CLUBS), legal)
    }

    @Test
    fun `void in led suit may play anything`() {
        val hand = listOf(Rank.ACE of Suit.HEARTS, Rank.FIVE of Suit.SPADES)
        val legal = spades.legalFollows(hand, ledSuit = Suit.DIAMONDS)
        assertEquals(hand.toSet(), legal.toSet())
    }

    @Test
    fun `joker is the top card at no-trump and always playable`() {
        // Joker present, hearts led, hand also has a heart -> must-follow set, but joker still legal.
        val hand = listOf(Joker, Rank.KING of Suit.HEARTS, Rank.FIVE of Suit.SPADES)
        val legal = noTrump.legalFollows(hand, ledSuit = Suit.HEARTS)
        assertTrue(Joker in legal)
        assertTrue((Rank.KING of Suit.HEARTS) in legal)
        assertFalse((Rank.FIVE of Suit.SPADES) in legal) // off-suit, not joker
    }

    @Test
    fun `joker wins at no-trump`() {
        val trick = listOf(
            play(0, Rank.ACE of Suit.HEARTS),
            play(1, Joker),
            play(2, Rank.KING of Suit.HEARTS),
        )
        assertEquals(Seat(1), noTrump.winner(trick))
    }

    @Test
    fun `joker led at no-trump nominates the suit others must follow`() {
        val trick = listOf(
            play(0, Joker, nominated = Suit.CLUBS),
            play(1, Rank.ACE of Suit.CLUBS),
        )
        // Joker still wins, and the led suit was clubs.
        assertEquals(Suit.CLUBS, noTrump.ledSuitOf(trick.first()))
        assertEquals(Seat(0), noTrump.winner(trick))
    }
}
