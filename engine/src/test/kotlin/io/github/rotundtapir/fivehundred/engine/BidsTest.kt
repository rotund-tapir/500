// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BidsTest {
    private val s = ScoreSchedule.Avondale

    @Test
    fun `Avondale point values`() {
        assertEquals(40, s.value(Bid.Named(6, Trump.SPADES)))
        assertEquals(120, s.value(Bid.Named(6, Trump.NO_TRUMP)))
        assertEquals(140, s.value(Bid.Named(7, Trump.SPADES)))
        assertEquals(240, s.value(Bid.Named(8, Trump.SPADES)))
        assertEquals(500, s.value(Bid.Named(10, Trump.HEARTS)))
        assertEquals(520, s.value(Bid.Named(10, Trump.NO_TRUMP)))
        assertEquals(250, s.value(Bid.Misere))
        assertEquals(500, s.value(Bid.OpenMisere))
    }

    @Test
    fun `ladder contains 25 named bids plus two miseres`() {
        assertEquals(27, s.ladder.size)
        assertEquals(Bid.OpenMisere, s.ladder.last()) // highest of all
    }

    @Test
    fun `Misere ranks between 8 spades and 8 clubs`() {
        assertTrue(s.outranks(Bid.Misere, Bid.Named(8, Trump.SPADES)))
        assertTrue(s.outranks(Bid.Named(8, Trump.CLUBS), Bid.Misere))
        // ...and comfortably above all 7-level bids
        assertTrue(s.outranks(Bid.Misere, Bid.Named(7, Trump.NO_TRUMP)))
    }

    @Test
    fun `Open Misere outranks the top suit bid despite equal points`() {
        assertEquals(s.value(Bid.OpenMisere), s.value(Bid.Named(10, Trump.HEARTS)))
        assertTrue(s.outranks(Bid.OpenMisere, Bid.Named(10, Trump.NO_TRUMP)))
    }

    @Test
    fun `higher level and higher denomination outrank`() {
        assertTrue(s.outranks(Bid.Named(7, Trump.SPADES), Bid.Named(6, Trump.NO_TRUMP)))
        assertTrue(s.outranks(Bid.Named(6, Trump.HEARTS), Bid.Named(6, Trump.DIAMONDS)))
        assertEquals(-1, s.rank(Bid.Pass))
    }
}
