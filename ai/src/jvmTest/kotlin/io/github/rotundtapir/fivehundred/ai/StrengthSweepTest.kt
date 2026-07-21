// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.StrategyPlayer
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import kotlin.random.Random
import kotlin.test.Test

/**
 * Strength-measurement harness, not a regression gate: sweeps the advanced bot's world cap and
 * reports its head-to-head win rate against the heuristic, approximating how sample starvation on
 * slow platforms (phone-browser wasm ≈ 8–32 worlds/decision inside the budgets; native app ≈ the
 * 192 cap) trades off against strength. Deterministic given the seeds. Prints a table to stdout
 * (surfaces in the jvmTest XML report's system-out).
 *
 * `@Disabled` because it takes ~4–5 minutes (250 capped matches) — far too slow for the pre-commit
 * `jvmTest`. Remove the annotation to rerun (like ai's TutorialTraceGenerator). Last run
 * (2026-07-21, 50 matches/cap): 8→58%, 16→66%, 32→68%, 64→82%, 128→78% advanced wins — strength
 * saturates around 64 worlds/decision.
 */
@Disabled("measurement harness, ~5 min — remove the annotation to rerun")
class StrengthSweepTest {

    private fun fixedConfig(worlds: Int) = SearchConfig(
        maxDeterminizations = worlds,
        minDeterminizations = worlds / 2,
        batchSize = 4,
        timeBudgetEnabled = false,
    )

    private fun capped(rules: FiveHundredRules, handCap: Int) =
        object : GameRules<GameState, Action, PlayerView> by rules {
            override fun isTerminal(state: GameState): Boolean =
                rules.isTerminal(state) || state.handNumber > handCap
        }

    /** One 4-player match: [advancedTeam]'s seats get the search bot, the others the heuristic. */
    private suspend fun playMatch(seed: Long, advancedTeam: Int, worlds: Int): GameState {
        val rules = FiveHundredRules()
        val heuristic = FiveHundredBot()
        val players = (0 until 4).associate { i ->
            Seat(i) to if (i % 2 == advancedTeam) {
                val bot = AdvancedBot(rules, config = fixedConfig(worlds))
                val rng = Random(seed + i)
                Player<PlayerView, Action> { v -> bot.decide(v, rng) }
            } else {
                StrategyPlayer(heuristic, Random(seed + i))
            }
        }
        return GameDriver(capped(rules, handCap = 30), players).play(rules.newGame(seed))
    }

    @Test
    fun `sweep world caps and report advanced vs heuristic win rate`() = runBlocking {
        val matchesPerCap = 50
        println("cap,advancedWins,heuristicWins,draws,advancedWinPct,avgMargin")
        for (worlds in listOf(8, 16, 32, 64, 128)) {
            var advWins = 0
            var draws = 0
            var marginSum = 0L
            for (seed in 1L..matchesPerCap) {
                val advTeam = (seed % 2).toInt() // alternate teams to cancel seat/deal bias
                val terminal = playMatch(seed * 1000 + worlds, advTeam, worlds)
                val adv = terminal.scores[advTeam] ?: 0
                val heu = terminal.scores[1 - advTeam] ?: 0
                if (adv > heu) advWins++ else if (adv == heu) draws++
                marginSum += (adv - heu)
            }
            val losses = matchesPerCap - advWins - draws
            val pct = 100.0 * advWins / matchesPerCap
            println("$worlds,$advWins,$losses,$draws,${"%.0f".format(pct)}%,${marginSum / matchesPerCap}")
        }
    }
}
