// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.core.Seat

/**
 * Scores a completed hand.
 *
 * Named contracts: the declaring side scores the contract value if it wins at least the bid number of
 * tricks (a clean sweep of all ten is worth at least 250 even for a cheaper bid), or loses the value
 * if it falls short; the defenders always score 10 per trick they take. Misère / Open Misère: the
 * declarer scores the value only by taking no tricks, otherwise loses it, and the defenders score
 * nothing.
 */
fun scoreHand(
    contract: Contract,
    tricksWon: Map<Seat, Int>,
    schedule: ScoreSchedule = ScoreSchedule.Avondale,
): HandResult {
    val declarerTeam = teamOf(contract.declarer)
    val otherTeam = 1 - declarerTeam
    val declarerTricks = tricksWon.entries.filter { teamOf(it.key) == declarerTeam }.sumOf { it.value }
    val value = schedule.value(contract.bid)

    val declarerDelta: Int
    val otherDelta: Int
    val made: Boolean

    if (contract.isMisere) {
        made = declarerTricks == 0
        declarerDelta = if (made) value else -value
        otherDelta = 0
    } else {
        made = declarerTricks >= contract.level
        declarerDelta = when {
            !made -> -value
            declarerTricks == TRICKS_PER_HAND && value < 250 -> 250 // all-ten bonus floor
            else -> value
        }
        val defenderTricks = TRICKS_PER_HAND - declarerTricks
        otherDelta = 10 * defenderTricks
    }

    return HandResult(
        contract = contract,
        declarerTricks = declarerTricks,
        made = made,
        teamDeltas = mapOf(declarerTeam to declarerDelta, otherTeam to otherDelta),
    )
}

/**
 * Determines the match winner after applying a hand's deltas, or `null` if the match continues.
 *
 * The bidding side wins by reaching +500 on a made contract; either side loses (the other wins) by
 * dropping to −500. A non-bidding side's points alone do not end the match — it must win its own
 * contract — matching common house rules.
 */
fun determineWinner(newScores: Map<Int, Int>, result: HandResult): Int? {
    val declarerTeam = teamOf(result.contract.declarer)
    val otherTeam = 1 - declarerTeam
    return when {
        result.made && (newScores[declarerTeam] ?: 0) >= WINNING_SCORE -> declarerTeam
        (newScores[declarerTeam] ?: 0) <= -WINNING_SCORE -> otherTeam
        (newScores[otherTeam] ?: 0) <= -WINNING_SCORE -> declarerTeam
        else -> null
    }
}
