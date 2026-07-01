// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.CardColor
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Deck500Test {
    private val suited = fiveHundredDeck.filterIsInstance<SuitedCard>()

    @Test
    fun `deck has exactly 43 cards`() {
        assertEquals(43, fiveHundredDeck.size)
        assertEquals(43, fiveHundredDeck.toSet().size) // all distinct
    }

    @Test
    fun `red suits run 4 to Ace, black suits run 5 to Ace`() {
        for (suit in listOf(Suit.HEARTS, Suit.DIAMONDS)) {
            assertEquals(11, suited.count { it.suit == suit })
            assertTrue(suited.any { it.suit == suit && it.rank == Rank.FOUR })
        }
        for (suit in listOf(Suit.SPADES, Suit.CLUBS)) {
            assertEquals(10, suited.count { it.suit == suit })
            assertTrue(suited.none { it.suit == suit && it.rank == Rank.FOUR })
        }
    }

    @Test
    fun `no twos or threes anywhere, no black fours, exactly one joker`() {
        assertTrue(suited.none { it.rank == Rank.TWO || it.rank == Rank.THREE })
        assertTrue(suited.none { it.rank == Rank.FOUR && it.color == CardColor.BLACK })
        assertEquals(1, fiveHundredDeck.count { it is Joker })
    }

    @Test
    fun `deals 10 to each of 4 players with a 3-card kitty`() {
        assertEquals(PLAYERS * HAND_SIZE + KITTY_SIZE, fiveHundredDeck.size)
    }
}
