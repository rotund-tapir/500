// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.ScoreSchedule
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.yield

/**
 * Tuning knobs for [AdvancedBot]'s search. The defaults are the production budgets; tests use
 * [timeBudgetEnabled] = false with a small [maxDeterminizations] so a decision is a pure function
 * of (view, tracker state, Random) — reproducible on any machine.
 */
data class SearchConfig(
    /** Wall-clock cap per bidding decision. */
    val bidBudget: Duration = 3.seconds,
    /** Wall-clock cap per kitty exchange (once per hand, between the bid and trick budgets). */
    val kittyBudget: Duration = 2.seconds,
    /** Wall-clock cap per card play. */
    val playBudget: Duration = 1.seconds,
    /** Hard cap on sampled worlds per decision — fast devices stop well before the wall clock. */
    val maxDeterminizations: Int = 192,
    /**
     * Worlds sampled before racing elimination may start dropping arms. For BIDDING decisions it
     * is also a floor: at least this many worlds are sampled even if [bidBudget] has already
     * expired, so a slow single-threaded platform (phone-browser wasm) never commits to a contract
     * off a statistically meaningless handful of samples. A floored bid may softly exceed
     * [bidBudget] on very slow devices; trick and kitty decisions keep their hard deadline.
     */
    val minDeterminizations: Int = 32,
    /** Racing elimination runs every this-many worlds (once past [minDeterminizations]). */
    val batchSize: Int = 8,
    /** False disables the wall clock entirely: fixed-iteration deterministic mode for tests. */
    val timeBudgetEnabled: Boolean = true,
)

/**
 * A search-based AI for 500: determinized flat Monte Carlo with paired sampling and racing
 * early-stops. Much stronger than [FiveHundredBot] (it card-counts, values ruffs and evaluates
 * bids against actual score outcomes) while staying battery-friendly — obvious decisions collapse
 * after a couple of batches, forced moves return without sampling at all, and the budgets in
 * [SearchConfig] are hard caps, not typical costs.
 *
 * Each decision evaluates every candidate action against the *same* sampled worlds (hidden hands
 * consistent with what this seat has observed, via [SeenTracker]/[Determinizer]), rolling each
 * world out to the end of the current hand with [fallback] as everyone's policy and averaging the
 * team score differential. Any failure inside the search degrades to [fallback]'s answer.
 *
 * Stateful ([SeenTracker] remembers tricks the view has forgotten): create one instance per bot
 * seat per game. [decide] is `suspend` and yields between worlds so the single-threaded wasm
 * event loop stays responsive; on the JVM run it on a background dispatcher (see
 * [AdvancedBotPlayer]).
 */
class AdvancedBot(
    private val rules: FiveHundredRules,
    private val schedule: ScoreSchedule = ScoreSchedule.Avondale,
    private val config: SearchConfig = SearchConfig(),
    private val fallback: FiveHundredBot = FiveHundredBot(schedule),
) {
    private val tracker = SeenTracker()
    private val determinizer = Determinizer(rules.playerCount)

    /** The action to take for [view]. Always legal; falls back to [fallback] on any search failure. */
    suspend fun decide(view: PlayerView, random: Random): Action {
        tracker.observe(view)
        val searched = try {
            searchDecision(view, random)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Throwable,
        ) {
            null // any search failure must degrade to the heuristic, never crash the game
        }
        val action = searched?.takeIf { isLegal(it, view) } ?: fallback.decide(view, random)
        if (action is Action.ExchangeKitty) tracker.recordMyDiscards(action.discards)
        return action
    }

    private suspend fun searchDecision(view: PlayerView, random: Random): Action? = when (view.phase) {
        // Bids are the highest-stakes decisions and the ones sample noise hurts most, so they get
        // the minDeterminizations floor even past the deadline (see SearchConfig).
        Phase.BIDDING -> search(view, bidArms(view), config.bidBudget, random, minWorlds = config.minDeterminizations)
        Phase.KITTY -> search(view, kittyArms(view), config.kittyBudget, random)
        Phase.PLAY -> search(view, playArms(view), config.playBudget, random)
        Phase.COMPLETE -> error("No action at COMPLETE")
    }

    // --- Search core -------------------------------------------------------------------------

    private suspend fun search(
        view: PlayerView,
        arms: List<Action>,
        budget: Duration,
        random: Random,
        minWorlds: Int = 1,
    ): Action? {
        if (arms.size <= 1) return arms.firstOrNull() // forced move: no sampling, no budget spent
        val start = TimeSource.Monotonic.markNow()
        val stats = List(arms.size) { RunningStat() }
        val live = arms.indices.toMutableList()
        var worlds = 0
        while (worlds < config.maxDeterminizations && live.size > 1 && withinBudget(start, budget, worlds, minWorlds)) {
            val world = determinizer.sample(view, tracker, random)
            // Paired sampling: every live arm scores against the same world, cancelling deal variance.
            live.forEach { stats[it].add(evaluate(world, view.seat, view.myTeam, arms[it], random)) }
            worlds++
            if (worlds >= config.minDeterminizations && worlds % config.batchSize == 0) {
                raceEliminate(live, stats)
            }
            yield() // keep the single-threaded wasm event loop responsive during long thinks
        }
        return arms[live.maxBy { stats[it].mean }]
    }

    private fun withinBudget(start: TimeMark, budget: Duration, worlds: Int, minWorlds: Int): Boolean =
        worlds < minWorlds || !config.timeBudgetEnabled || start.elapsedNow() < budget

    /** Applies [arm] to [world] and plays the hand out with [fallback] as everyone's policy. */
    private fun evaluate(world: GameState, seat: Seat, myTeam: Int, arm: Action, random: Random): Double {
        val startScores = world.scores
        val startHand = world.handNumber
        var s = rules.apply(world, seat, arm)
        // The hand boundary covers every exit: hand scored (handNumber bumps), pass-out redeal
        // (handNumber bumps, no score change), or match complete.
        while (s.phase != Phase.COMPLETE && s.handNumber == startHand) {
            val actor = rules.currentActor(s) ?: break
            s = rules.apply(s, actor, fallback.decide(rules.view(s, actor), random))
        }
        return reward(s, startScores, myTeam)
    }

    /** Hand-level team score differential, plus a decisive bonus when the rollout ends the match. */
    private fun reward(end: GameState, startScores: Map<Int, Int>, myTeam: Int): Double {
        fun delta(team: Int) = (end.scores[team] ?: 0) - (startScores[team] ?: 0)
        val bestOther = (0 until end.teamCount).filter { it != myTeam }.maxOfOrNull { delta(it) } ?: 0
        val winBonus = when (end.winner) {
            null -> 0.0
            myTeam -> WIN_BONUS
            else -> -WIN_BONUS
        }
        return (delta(myTeam) - bestOther).toDouble() + winBonus
    }

    /** Drops every arm whose confidence interval sits wholly below the best arm's. */
    private fun raceEliminate(live: MutableList<Int>, stats: List<RunningStat>) {
        val best = live.maxBy { stats[it].mean }
        val bestLow = stats[best].mean - RACE_Z * stats[best].standardError
        live.removeAll { it != best && stats[it].mean + RACE_Z * stats[it].standardError < bestLow }
    }

    // --- Candidate actions per phase ---------------------------------------------------------

    /**
     * Pass plus a pruned slate of contracts: per denomination the lowest legal level and the
     * highest the heuristic's trick estimate stretches to, plus a fancied Misère / Open Misère.
     * Each rollout then samples a full deal (kitty included) and lets the heuristic finish the
     * auction for everyone, so "what if I win this contract" needs no special machinery.
     */
    private fun bidArms(view: PlayerView): List<Action> {
        val legal = view.legalBids
        val candidates = mutableSetOf<Bid>()
        Trump.entries.forEach { trump ->
            val ceiling = floor(fallback.estimateTricks(view.hand, trump)).toInt() + 1
            val named = legal.filterIsInstance<Bid.Named>().filter { it.trump == trump && it.level <= ceiling }
            named.minByOrNull { it.level }?.let { candidates += it }
            named.maxByOrNull { it.level }?.let { candidates += it }
        }
        val fancied = fallback.candidateBids(view.hand)
        listOf(Bid.Misere, Bid.OpenMisere)
            .filter { it in legal && it in fancied }
            .forEach { candidates += it }
        val ranked = candidates.sortedByDescending { schedule.rank(it) }.take(MAX_BID_ARMS - 1)
        return (ranked + Bid.Pass).map { Action.PlaceBid(it) }
    }

    /**
     * C(13,3) = 286 discards is too many arms: keep the heuristic's own pick, every "create a
     * void" discard (dump a whole short side suit — the plays a greedy keep/dump metric misses),
     * and all 3-subsets of the [KITTY_CANDIDATE_POOL] most discardable cards.
     */
    private fun kittyArms(view: PlayerView): List<Action> {
        val contract = view.contract ?: return emptyList()
        val eval = TrickEvaluator(contract.trump)
        val byDiscardability =
            if (contract.isMisere) view.hand.sortedByDescending { fallback.misereDanger(it) }
            else view.hand.sortedBy { fallback.rawStrength(it, eval) }
        val combos = buildList {
            add(fallback.chooseDiscards(view.hand, contract.trump, contract.isMisere))
            if (!contract.isMisere) addAll(voidCandidates(view.hand, eval, byDiscardability))
            addAll(threeSubsets(byDiscardability.take(KITTY_CANDIDATE_POOL)))
        }
        return combos.map { it.toSet() }.distinct().take(MAX_KITTY_ARMS)
            .map { Action.ExchangeKitty(it.toList()) }
    }

    /** For each non-trump side suit short enough to shed entirely: it plus weakest-card filler. */
    private fun voidCandidates(
        hand: List<Card>,
        eval: TrickEvaluator,
        byDiscardability: List<Card>,
    ): List<List<Card>> =
        hand.filterNot { eval.isTrump(it) }
            .groupBy { eval.effectiveSuit(it) }
            .values
            .filter { it.size in 1..KITTY_SIZE }
            .map { suit -> suit + byDiscardability.filterNot { it in suit }.take(KITTY_SIZE - suit.size) }

    private fun threeSubsets(cards: List<Card>): List<List<Card>> =
        cards.indices.flatMap { i ->
            (i + 1 until cards.size).flatMap { j ->
                (j + 1 until cards.size).map { k -> listOf(cards[i], cards[j], cards[k]) }
            }
        }

    /**
     * Every legal play, collapsed to one representative per equivalence class, with the Joker led
     * at no-trump expanded into its four nomination arms (nominating is never worse than not).
     */
    private fun playArms(view: PlayerView): List<Action> {
        val legal = view.legalPlays
        if (legal.size <= 1) return legal.map { Action.PlayCard(it) }
        val trump = view.trump ?: Trump.NO_TRUMP
        val eval = TrickEvaluator(trump)
        return reduceEquivalent(legal, view, eval).flatMap { card ->
            if (card is Joker && trump == Trump.NO_TRUMP && view.currentTrick.isEmpty()) {
                Suit.entries.map { Action.PlayCard(card, nominate = it) }
            } else {
                listOf(Action.PlayCard(card))
            }
        }
    }

    /**
     * Collapses cards that are interchangeable this trick — same effective suit with no card
     * another player could still hold ranking strictly between them — keeping the lowest of each
     * class. Typically halves the arm count late in a hand.
     */
    private fun reduceEquivalent(legal: List<Card>, view: PlayerView, eval: TrickEvaluator): List<Card> {
        val known = buildSet {
            addAll(view.hand)
            addAll(tracker.seenPlays)
            addAll(tracker.myDiscards)
        }
        val unseen = determinizer.deck.filterNot { it in known }
        return legal.groupBy { eval.effectiveSuit(it) }.flatMap { (suit, cards) ->
            if (suit == null) cards else collapse(cards, unseen, suit, eval)
        }
    }

    private fun collapse(cards: List<Card>, unseen: List<Card>, suit: Suit, eval: TrickEvaluator): List<Card> {
        val rivals = unseen.filter { eval.effectiveSuit(it) == suit }.map { eval.strength(it, suit) }
        val sorted = cards.sortedBy { eval.strength(it, suit) }
        val kept = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            val lo = eval.strength(sorted[i - 1], suit)
            val hi = eval.strength(sorted[i], suit)
            if (rivals.any { it in (lo + 1) until hi }) kept += sorted[i]
        }
        return kept
    }

    // --- Safety ------------------------------------------------------------------------------

    /** Belt-and-braces legality check against the view before an action leaves this bot. */
    private fun isLegal(action: Action, view: PlayerView): Boolean = when (action) {
        is Action.PlaceBid -> action.bid in view.legalBids
        is Action.PlayCard -> action.card in view.legalPlays
        is Action.ExchangeKitty ->
            action.discards.size == view.mustDiscard &&
                action.discards.toSet().size == action.discards.size &&
                view.hand.containsAll(action.discards)
    }

    private companion object {
        /** z-score for racing elimination: ~95% confidence before an arm is dropped. */
        const val RACE_Z = 2.0

        /** Reward bonus when a rollout ends the whole match — winning at 500 dwarfs hand points. */
        const val WIN_BONUS = 500.0

        const val MAX_BID_ARMS = 8
        const val KITTY_CANDIDATE_POOL = 7
        const val MAX_KITTY_ARMS = 36
    }
}

/** Welford running mean/variance for one arm's rewards. */
internal class RunningStat {
    var n = 0
        private set
    var mean = 0.0
        private set
    private var m2 = 0.0

    fun add(x: Double) {
        n++
        val d = x - mean
        mean += d / n
        m2 += d * (x - mean)
    }

    /** Standard error of the mean; infinite below two samples so nothing is eliminated early. */
    val standardError: Double
        get() = if (n < 2) Double.POSITIVE_INFINITY else sqrt(m2 / (n - 1) / n)
}
