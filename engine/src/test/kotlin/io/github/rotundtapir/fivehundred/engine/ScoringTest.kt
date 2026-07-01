// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Seat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringTest {
    // Declarer = seat 0 (team 0); partner seat 2; opponents seats 1 & 3 (team 1).
    private fun tricks(t0: Int, t1: Int, t2: Int, t3: Int) =
        mapOf(Seat(0) to t0, Seat(1) to t1, Seat(2) to t2, Seat(3) to t3)

    @Test
    fun `made suit contract scores value, defenders score ten per trick`() {
        val r = scoreHand(Contract(Seat(0), Bid.Named(7, Trump.SPADES)), tricks(5, 2, 2, 1))
        assertTrue(r.made)
        assertEquals(7, r.declarerTricks)
        assertEquals(140, r.teamDeltas[0])
        assertEquals(30, r.teamDeltas[1]) // 3 defender tricks * 10
    }

    @Test
    fun `failed suit contract loses value, defenders still score`() {
        val r = scoreHand(Contract(Seat(0), Bid.Named(7, Trump.SPADES)), tricks(4, 3, 2, 1))
        assertFalse(r.made)
        assertEquals(6, r.declarerTricks)
        assertEquals(-140, r.teamDeltas[0])
        assertEquals(40, r.teamDeltas[1])
    }

    @Test
    fun `winning all ten on a cheap bid earns the 250 floor`() {
        val r = scoreHand(Contract(Seat(0), Bid.Named(6, Trump.SPADES)), tricks(6, 0, 4, 0))
        assertTrue(r.made)
        assertEquals(10, r.declarerTricks)
        assertEquals(250, r.teamDeltas[0]) // not the 40-point face value
        assertEquals(0, r.teamDeltas[1])
    }

    @Test
    fun `misere made and failed`() {
        val made = scoreHand(Contract(Seat(0), Bid.Misere), tricks(0, 6, 0, 4))
        assertTrue(made.made)
        assertEquals(250, made.teamDeltas[0])
        assertEquals(0, made.teamDeltas[1]) // defenders never score on a misère

        val failed = scoreHand(Contract(Seat(0), Bid.Misere), tricks(1, 5, 0, 4))
        assertFalse(failed.made)
        assertEquals(-250, failed.teamDeltas[0])
    }

    @Test
    fun `open misere scores 500`() {
        val r = scoreHand(Contract(Seat(0), Bid.OpenMisere), tricks(0, 7, 0, 3))
        assertEquals(500, r.teamDeltas[0])
    }

    @Test
    fun `bidding team wins the match by reaching 500 on a made contract`() {
        val result = scoreHand(Contract(Seat(0), Bid.Named(8, Trump.HEARTS)), tricks(8, 1, 0, 1))
        val newScores = mapOf(0 to 520, 1 to 120)
        assertEquals(0, determineWinner(newScores, result))
    }

    @Test
    fun `a team going to minus 500 loses`() {
        val result = scoreHand(Contract(Seat(0), Bid.Named(8, Trump.HEARTS)), tricks(3, 4, 1, 2))
        assertFalse(result.made)
        val newScores = mapOf(0 to -520, 1 to 200)
        assertEquals(1, determineWinner(newScores, result)) // team 1 wins because team 0 is out the back
    }

    @Test
    fun `match continues below the threshold`() {
        val result = scoreHand(Contract(Seat(0), Bid.Named(7, Trump.SPADES)), tricks(7, 1, 0, 2))
        assertNull(determineWinner(mapOf(0 to 300, 1 to 120), result))
    }
}
