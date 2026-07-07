// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Seat
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 6-player, three-teams-of-two variant (partners opposite: 0&3, 1&4, 2&5). */
class ThreeTeamsTest {

    private fun rules() = FiveHundredRules(playerCount = 6, teamCount = 3)

    // --- Construction and team assignment ----------------------------------------------------------

    @Test
    fun `three teams of two seat partners opposite`() {
        assertEquals(0, teamOf(Seat(0), 3))
        assertEquals(1, teamOf(Seat(1), 3))
        assertEquals(2, teamOf(Seat(2), 3))
        assertEquals(0, teamOf(Seat(3), 3))
        assertEquals(1, teamOf(Seat(4), 3))
        assertEquals(2, teamOf(Seat(5), 3))

        assertEquals(listOf(Seat(3)), teammatesOf(Seat(0), 6, 3))
        assertEquals(listOf(Seat(4)), teammatesOf(Seat(1), 6, 3))
        assertEquals(listOf(Seat(5)), teammatesOf(Seat(2), 6, 3))
        assertEquals(listOf(Seat(0)), teammatesOf(Seat(3), 6, 3))
        assertEquals(listOf(Seat(1)), teammatesOf(Seat(4), 6, 3))
        assertEquals(listOf(Seat(2)), teammatesOf(Seat(5), 6, 3))
    }

    @Test
    fun `three teams are only supported at 6 players`() {
        assertEquals(3, rules().teamCount)
        assertThrows<IllegalArgumentException> { FiveHundredRules(playerCount = 2, teamCount = 3) }
        assertThrows<IllegalArgumentException> { FiveHundredRules(playerCount = 4, teamCount = 3) }
        // And nothing but 2 or 3 teams anywhere.
        assertThrows<IllegalArgumentException> { FiveHundredRules(playerCount = 6, teamCount = 1) }
        assertThrows<IllegalArgumentException> { FiveHundredRules(playerCount = 6, teamCount = 6) }
    }

    @Test
    fun `a new game starts all three teams at zero and views carry the team count`() {
        val rules = rules()
        val s = rules.newGame(seed = 11L)
        assertEquals(mapOf(0 to 0, 1 to 0, 2 to 0), s.scores)
        assertEquals(3, s.teamCount)
        for (i in 0 until 6) {
            val view = rules.view(s, Seat(i))
            assertEquals(3, view.teamCount)
            assertEquals(i % 3, view.myTeam)
        }
    }

    // --- A full hand -------------------------------------------------------------------------------

    /** Opener bids 6♠, everyone else passes; discard the first 3; play the first legal card. */
    private fun policy(view: PlayerView): Action = when (view.phase) {
        Phase.BIDDING -> Action.PlaceBid(if (view.highBid == null) Bid.Named(6, Trump.SPADES) else Bid.Pass)
        Phase.KITTY -> Action.ExchangeKitty(view.hand.take(KITTY_SIZE))
        Phase.PLAY -> Action.PlayCard(view.legalPlays.first())
        Phase.COMPLETE -> error("no action at COMPLETE")
    }

    @Test
    fun `a full hand scores every team - the declarer by the contract, each other team by its own tricks`() {
        val rules = rules()
        var s = rules.newGame(seed = 42L)
        // The engine redeals the moment the last card falls, so tally the final trick by hand.
        var finalTally: Map<Seat, Int>? = null
        var contract: Contract? = null
        while (!rules.isTerminal(s) && s.handNumber == 1) {
            val actor = rules.currentActor(s) ?: break
            val action = rules.legalActions(s, actor).firstOrNull()
                ?.takeIf { s.phase == Phase.PLAY }
                ?: policy(rules.view(s, actor))
            if (s.phase == Phase.PLAY &&
                s.trickNumber == TRICKS_PER_HAND - 1 &&
                s.currentTrick.size == s.activeSeats.size - 1
            ) {
                contract = s.contract
                val play = action as Action.PlayCard
                val winner = TrickEvaluator(s.contract!!.trump).winner(s.currentTrick + TrickPlay(actor, play.card))
                finalTally = s.tricksWon.toMutableMap().apply { put(winner, (get(winner) ?: 0) + 1) }
            }
            s = rules.apply(s, actor, action)
        }

        val result = s.lastHandResult
        assertNotNull(result)
        assertNotNull(finalTally)
        assertNotNull(contract)

        fun teamTricks(team: Int) = finalTally.entries.filter { it.key.index % 3 == team }.sumOf { it.value }

        val declarerTeam = teamOf(contract.declarer, 3)
        assertEquals(setOf(0, 1, 2), result.teamDeltas.keys, "every team gets a delta")
        assertEquals(teamTricks(declarerTeam), result.declarerTricks)

        // Each defending team scores 10 × the tricks its own members took.
        for (team in 0 until 3) {
            if (team == declarerTeam) continue
            assertEquals(10 * teamTricks(team), result.teamDeltas[team], "team $team scores its own tricks")
        }
        // Declarer's team scores ±contract value (6♠ = 40; a clean sweep floors at 250).
        val expectedDeclarerDelta = when {
            !result.made -> -40
            result.declarerTricks == TRICKS_PER_HAND -> 250
            else -> 40
        }
        assertEquals(expectedDeclarerDelta, result.teamDeltas[declarerTeam])
        // Consistency: the defenders' deltas sum to 10 × the tricks the declarer's side did not take.
        val defenderSum = result.teamDeltas.filterKeys { it != declarerTeam }.values.sum()
        assertEquals(10 * (TRICKS_PER_HAND - result.declarerTricks), defenderSum)
        // And the new match scores are exactly the deltas (from a zero start).
        assertEquals(result.teamDeltas, s.scores)
    }

    // --- Misère ------------------------------------------------------------------------------------

    @Test
    fun `on a misere exactly one teammate sits out and five play`() {
        val rules = rules()
        var s = rules.newGame(seed = 5L)
        // Misère is gated behind a seven bid: the opener bids 7♠, the next seat raises 7♣,
        // then the opener calls Misère on their second turn and everyone else passes.
        val declarer = rules.currentActor(s)!!
        s = rules.apply(s, declarer, Action.PlaceBid(Bid.Named(7, Trump.SPADES)))
        s = rules.apply(s, rules.currentActor(s)!!, Action.PlaceBid(Bid.Named(7, Trump.CLUBS)))
        repeat(4) { s = rules.apply(s, rules.currentActor(s)!!, Action.PlaceBid(Bid.Pass)) }
        s = rules.apply(s, declarer, Action.PlaceBid(Bid.Misere))
        s = rules.apply(s, rules.currentActor(s)!!, Action.PlaceBid(Bid.Pass))
        s = rules.apply(s, declarer, Action.ExchangeKitty(s.hands[declarer]!!.take(KITTY_SIZE)))

        val sittingOut = (0 until 6).map(::Seat).filter { it !in s.activeSeats }
        assertEquals(5, s.activeSeats.size, "five seats play a 3-team misère")
        assertEquals(teammatesOf(declarer, 6, 3), sittingOut, "exactly the declarer's one partner sits out")
    }

    @Test
    fun `misere scoring gives every defending team zero`() {
        val contract = Contract(Seat(0), Bid.Misere)
        val made = scoreHand(
            contract,
            mapOf(Seat(0) to 0, Seat(1) to 4, Seat(2) to 3, Seat(4) to 2, Seat(5) to 1),
            teamCount = 3,
        )
        assertTrue(made.made)
        assertEquals(mapOf(0 to 250, 1 to 0, 2 to 0), made.teamDeltas)

        val failed = scoreHand(
            contract,
            mapOf(Seat(0) to 1, Seat(1) to 4, Seat(2) to 3, Seat(4) to 1, Seat(5) to 1),
            teamCount = 3,
        )
        assertEquals(mapOf(0 to -250, 1 to 0, 2 to 0), failed.teamDeltas)
    }

    // --- Match end ---------------------------------------------------------------------------------

    @Test
    fun `declarer team reaching 500 on a made contract wins`() {
        val result = HandResult(
            contract = Contract(Seat(1), Bid.Named(8, Trump.HEARTS)),
            declarerTricks = 8,
            made = true,
            teamDeltas = mapOf(0 to 10, 1 to 300, 2 to 10),
        )
        assertEquals(1, determineWinner(mapOf(0 to 200, 1 to 510, 2 to 100), result, 3))
        assertNull(determineWinner(mapOf(0 to 200, 1 to 490, 2 to 100), result, 3))
    }

    @Test
    fun `a team going out the back ends the game and the best of the other two wins`() {
        val result = HandResult(
            contract = Contract(Seat(0), Bid.Named(10, Trump.NO_TRUMP)),
            declarerTricks = 6,
            made = false,
            teamDeltas = mapOf(0 to -520, 1 to 30, 2 to 10),
        )
        // Team 0 is out the back; team 2 has the higher score of the survivors.
        assertEquals(2, determineWinner(mapOf(0 to -510, 1 to 120, 2 to 310), result, 3))
        // Ties between the survivors break to the lowest team index.
        assertEquals(1, determineWinner(mapOf(0 to -510, 1 to 120, 2 to 120), result, 3))
        // A non-declaring team's high score alone never ends the match.
        assertNull(determineWinner(mapOf(0 to -490, 1 to 620, 2 to 120), result, 3))
    }
}
