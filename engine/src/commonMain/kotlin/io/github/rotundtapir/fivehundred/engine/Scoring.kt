// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Seat

/**
 * Scores a completed hand for a table of [teamCount] teams.
 *
 * Named contracts: the declaring team scores the contract value if it wins at least the bid number of
 * tricks (a clean sweep of all ten is worth at least 250 even for a cheaper bid), or loses the value
 * if it falls short; every other team scores 10 per trick its own members took. Misère / Open Misère:
 * the declarer scores the value only by taking no tricks, otherwise loses it, and the defenders score
 * nothing. [HandResult.teamDeltas] contains an entry for every team.
 */
fun scoreHand(
    contract: Contract,
    tricksWon: Map<Seat, Int>,
    schedule: ScoreSchedule = ScoreSchedule.Avondale,
    teamCount: Int = 2,
): HandResult {
    val declarerTeam = teamOf(contract.declarer, teamCount)
    fun teamTricks(team: Int): Int =
        tricksWon.entries.filter { teamOf(it.key, teamCount) == team }.sumOf { it.value }

    val declarerTricks = teamTricks(declarerTeam)
    val value = schedule.value(contract.bid)

    val made: Boolean
    val declarerDelta: Int

    if (contract.isMisere) {
        made = declarerTricks == 0
        declarerDelta = if (made) value else -value
    } else {
        made = declarerTricks >= contract.level
        declarerDelta = when {
            !made -> -value
            declarerTricks == TRICKS_PER_HAND && value < 250 -> 250 // all-ten bonus floor
            else -> value
        }
    }

    val teamDeltas = (0 until teamCount).associateWith { team ->
        when {
            team == declarerTeam -> declarerDelta
            contract.isMisere -> 0
            else -> 10 * teamTricks(team)
        }
    }

    return HandResult(
        contract = contract,
        declarerTricks = declarerTricks,
        made = made,
        teamDeltas = teamDeltas,
    )
}

/**
 * Determines the match winner after applying a hand's deltas, or `null` if the match continues.
 *
 * The bidding team wins by reaching +500 on a made contract; a non-bidding team's points alone do
 * not end the match — it must win its own contract — matching common house rules.
 *
 * Any team dropping to −500 ("out the back") ends the game immediately. With two teams the other
 * team simply wins; with three teams the house interpretation used here is that the *best-scoring*
 * of the remaining teams wins (ties broken by the lowest team index).
 */
fun determineWinner(newScores: Map<Int, Int>, result: HandResult, teamCount: Int = 2): Int? {
    val declarerTeam = teamOf(result.contract.declarer, teamCount)
    if (result.made && (newScores[declarerTeam] ?: 0) >= WINNING_SCORE) return declarerTeam

    val bust = (0 until teamCount).filter { (newScores[it] ?: 0) <= -WINNING_SCORE }
    if (bust.isEmpty()) return null
    // maxByOrNull keeps the first (lowest-index) team on ties.
    return (0 until teamCount).filter { it !in bust }.maxByOrNull { newScores[it] ?: 0 }
}
