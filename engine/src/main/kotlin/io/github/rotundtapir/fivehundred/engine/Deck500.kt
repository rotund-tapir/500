// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.buildDeck
import io.github.rotundtapir.cardkit.core.rangeTo

/**
 * The 43-card deck for 4-player 500: 4→A of the red suits (11 each), 5→A of the black suits
 * (10 each), plus a single Joker. Equivalent to a standard deck with the 2s, 3s and black 4s removed
 * and a Joker added.
 */
val fiveHundredDeck: List<Card> = buildDeck {
    suits(Suit.HEARTS, Suit.DIAMONDS) { ranks(Rank.FOUR..Rank.ACE) }
    suits(Suit.SPADES, Suit.CLUBS) { ranks(Rank.FIVE..Rank.ACE) }
    joker()
}

const val PLAYERS = 4
const val HAND_SIZE = 10
const val KITTY_SIZE = 3
const val TRICKS_PER_HAND = 10

/** The score at which a partnership wins (or, negated, loses "out the back door"). */
const val WINNING_SCORE = 500
