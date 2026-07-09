// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Seat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The kitty-exchange guards: the declarer must discard exactly three distinct cards from hand. */
class KittyExchangeTest {
    private val rules = FiveHundredRules()

    /** Drives a seeded auction so seat 1 wins 6♠ and reaches the KITTY phase with a 13-card hand. */
    private fun toKitty(seed: Long = 1L): GameState {
        var s = rules.newGame(seed, firstDealer = Seat(0))
        s = rules.apply(s, Seat(1), Action.PlaceBid(Bid.Named(6, Trump.SPADES)))
        s = rules.apply(s, Seat(2), Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, Seat(3), Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, Seat(0), Action.PlaceBid(Bid.Pass))
        check(s.phase == Phase.KITTY && s.contract?.declarer == Seat(1))
        return s
    }

    @Test
    fun `rejects fewer than three discards`() {
        val s = toKitty()
        val hand = s.hands.getValue(Seat(1))
        assertFailsWith<IllegalArgumentException> {
            rules.apply(s, Seat(1), Action.ExchangeKitty(hand.take(2)))
        }
    }

    @Test
    fun `rejects more than three discards`() {
        val s = toKitty()
        val hand = s.hands.getValue(Seat(1))
        assertFailsWith<IllegalArgumentException> {
            rules.apply(s, Seat(1), Action.ExchangeKitty(hand.take(4)))
        }
    }

    @Test
    fun `rejects duplicate discards`() {
        val s = toKitty()
        val card = s.hands.getValue(Seat(1)).first()
        assertFailsWith<IllegalArgumentException> {
            rules.apply(s, Seat(1), Action.ExchangeKitty(listOf(card, card, card)))
        }
    }

    @Test
    fun `rejects discarding a card not in hand`() {
        val s = toKitty()
        val hand = s.hands.getValue(Seat(1))
        val notInHand = FiveHundredRules().let {
            // Any card the declarer does not hold works; pick one from another seat.
            s.hands.getValue(Seat(0)).first { it !in hand }
        }
        assertFailsWith<IllegalArgumentException> {
            rules.apply(s, Seat(1), Action.ExchangeKitty(hand.take(2) + notInHand))
        }
    }

    @Test
    fun `a valid exchange shrinks the hand to ten and keeps the discards as the kitty`() {
        val s = toKitty()
        val discards = s.hands.getValue(Seat(1)).take(KITTY_SIZE)
        val next = rules.apply(s, Seat(1), Action.ExchangeKitty(discards))
        assertEquals(Phase.PLAY, next.phase)
        assertEquals(HAND_SIZE, next.hands.getValue(Seat(1)).size)
        assertEquals(discards, next.kitty)
        assertTrue(discards.none { it in next.hands.getValue(Seat(1)) }, "discards leave the hand")
    }
}
