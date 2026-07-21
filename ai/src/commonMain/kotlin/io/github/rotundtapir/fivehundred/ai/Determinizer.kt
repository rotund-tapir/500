// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Joker
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.BiddingState
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.KITTY_SIZE
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.TrickEvaluator
import io.github.rotundtapir.fivehundred.engine.TrickPlay
import io.github.rotundtapir.fivehundred.engine.Trump
import io.github.rotundtapir.fivehundred.engine.fiveHundredDeck
import kotlin.random.Random

/**
 * Accumulates what one bot seat has observed across a hand, beyond what a single [PlayerView]
 * carries. The view only exposes the current and the most recent trick, so cards from older tricks
 * would otherwise be forgotten and could be dealt back to opponents during determinization.
 *
 * One instance per bot seat per game: an *active* seat plays in every trick, so between consecutive
 * [observe] calls at most one trick completes and `lastTrick` + `currentTrick` cover every play
 * since the previous call. If observation ever misses a play the sampled worlds are merely less
 * informed, never inconsistent — missed cards stay in the unknown pool.
 */
internal class SeenTracker {
    private var handNumber = -1

    /** Every card observed hitting the felt this hand. */
    val seenPlays = mutableSetOf<Card>()

    /** Effective suits each seat has been proven void in (failed to follow). */
    val voids = mutableMapOf<Seat, MutableSet<Suit>>()

    /** The cards this seat itself buried after taking the kitty — hidden from everyone else. */
    var myDiscards: List<Card> = emptyList()
        private set

    /** Call at the top of every decision with the view being decided on. */
    fun observe(view: PlayerView) {
        if (view.handNumber != handNumber) {
            handNumber = view.handNumber
            seenPlays.clear()
            voids.clear()
            myDiscards = emptyList()
        }
        val eval = TrickEvaluator(view.trump ?: return) // nothing on the felt before a contract
        view.lastTrick?.let { record(it.plays, eval) }
        record(view.currentTrick, eval)
    }

    /** Call when this seat's kitty exchange is decided, so its discards never get re-dealt. */
    fun recordMyDiscards(cards: List<Card>) {
        myDiscards = cards
    }

    private fun record(plays: List<TrickPlay>, eval: TrickEvaluator) {
        if (plays.isEmpty()) return
        plays.forEach { seenPlays += it.card }
        val led = eval.ledSuitOf(plays.first()) ?: return
        plays.drop(1)
            // The Joker is always playable at no-trump, so it proves nothing about a void there;
            // in a suit contract an off-suit Joker is a ruff and proves the void like any trump.
            .filterNot { it.card is Joker && eval.trump == Trump.NO_TRUMP }
            .filter { eval.effectiveSuit(it.card) != led }
            .forEach { voids.getOrPut(it.seat) { mutableSetOf() } += led }
    }
}

/**
 * Samples full [GameState]s consistent with a [PlayerView] plus a [SeenTracker]'s observations, for
 * Monte-Carlo evaluation: hidden cards are dealt randomly to the other seats (respecting proven
 * voids where possible), and the public state is copied across verbatim.
 *
 * Cards nobody can see — the kitty another declarer buried, the twenty dead cards at 2 players —
 * simply stay unassigned; [io.github.rotundtapir.fivehundred.engine.FiveHundredRules.apply] never
 * checks card conservation, so such worlds replay perfectly legally.
 */
internal class Determinizer(playerCount: Int) {

    /** The full deck for this table size — also the universe for "which cards are still unseen". */
    val deck: List<Card> = fiveHundredDeck(playerCount)

    /** One sampled world: a [GameState] the reducer accepts, agreeing with everything [view] shows. */
    fun sample(view: PlayerView, tracker: SeenTracker, random: Random): GameState {
        val known = knownCards(view, tracker)
        val pool = ArrayDeque(deck.filterNot { it in known }.shuffled(random))
        val hands = dealHands(view, pool)
        if (view.phase == Phase.PLAY) {
            val eval = TrickEvaluator(view.trump ?: Trump.NO_TRUMP)
            repairVoids(hands, tracker.voids, eval, pool, fixedSeats(view))
        }
        // During BIDDING the kitty is still face down and must exist — the auction winner takes it
        // into hand. In later phases the reducer never reads it, so leave it empty.
        val kitty = if (view.phase == Phase.BIDDING) List(KITTY_SIZE) { pool.removeFirst() } else emptyList()
        return reconstruct(view, hands, kitty, random)
    }

    /** Cards whose location is certain: my hand, everything played, my discards, an exposed hand. */
    private fun knownCards(view: PlayerView, tracker: SeenTracker): Set<Card> = buildSet {
        addAll(view.hand)
        addAll(tracker.seenPlays)
        addAll(tracker.myDiscards)
        view.exposedDeclarerHand?.let { addAll(it) }
    }

    /** Seats whose hands are known exactly and must not be resampled or swapped into. */
    private fun fixedSeats(view: PlayerView): Set<Seat> = buildSet {
        add(view.seat)
        if (view.exposedDeclarerHand != null) view.contract?.let { add(it.declarer) }
    }

    private fun dealHands(view: PlayerView, pool: ArrayDeque<Card>): MutableMap<Seat, MutableList<Card>> {
        val hands = mutableMapOf<Seat, MutableList<Card>>()
        hands[view.seat] = view.hand.toMutableList()
        val exposed = view.exposedDeclarerHand
        val declarer = view.contract?.declarer
        if (exposed != null && declarer != null) hands[declarer] = exposed.toMutableList()
        view.handSizes.keys.sortedBy { it.index }
            .filterNot { it in hands }
            .forEach { seat -> hands[seat] = MutableList(view.handSizes.getValue(seat)) { pool.removeFirst() } }
        return hands
    }

    /**
     * Best-effort repair of sampled hands against proven voids: each card sitting in a suit its
     * holder cannot have is swapped with a conformant card from the unassigned [leftover] or from
     * another sampled hand. One pass; irreparable placements are accepted (the world stays legal to
     * replay, just slightly misinformed).
     */
    private fun repairVoids(
        hands: MutableMap<Seat, MutableList<Card>>,
        voids: Map<Seat, Set<Suit>>,
        eval: TrickEvaluator,
        leftover: ArrayDeque<Card>,
        fixed: Set<Seat>,
    ) {
        voids.keys.sortedBy { it.index }
            .filterNot { it in fixed }
            .filter { it in hands }
            .forEach { seat -> repairSeat(seat, hands, voids, eval, leftover, fixed) }
    }

    private fun repairSeat(
        seat: Seat,
        hands: MutableMap<Seat, MutableList<Card>>,
        voids: Map<Seat, Set<Suit>>,
        eval: TrickEvaluator,
        leftover: ArrayDeque<Card>,
        fixed: Set<Seat>,
    ) {
        val voidSuits = voids.getValue(seat)
        val hand = hands.getValue(seat)
        hand.indices
            .filter { eval.effectiveSuit(hand[it]) in voidSuits }
            .forEach { i ->
                if (!swapWithLeftover(hand, i, voidSuits, eval, leftover)) {
                    swapWithOtherHand(seat, hand, i, hands, voids, eval, fixed)
                }
            }
    }

    private fun swapWithLeftover(
        hand: MutableList<Card>,
        i: Int,
        voidSuits: Set<Suit>,
        eval: TrickEvaluator,
        leftover: ArrayDeque<Card>,
    ): Boolean {
        val li = leftover.indexOfFirst { eval.effectiveSuit(it) !in voidSuits }
        if (li < 0) return false
        val incoming = leftover[li]
        leftover[li] = hand[i]
        hand[i] = incoming
        return true
    }

    private fun swapWithOtherHand(
        seat: Seat,
        hand: MutableList<Card>,
        i: Int,
        hands: MutableMap<Seat, MutableList<Card>>,
        voids: Map<Seat, Set<Suit>>,
        eval: TrickEvaluator,
        fixed: Set<Seat>,
    ) {
        val outgoing = hand[i]
        val candidates = hands.keys.sortedBy { it.index }
            .filterNot { it == seat || it in fixed }
            // The displaced card must be legal for the other seat to hold.
            .filterNot { eval.effectiveSuit(outgoing) in voids[it].orEmpty() }
        for (other in candidates) {
            val otherHand = hands.getValue(other)
            val oi = otherHand.indexOfFirst { eval.effectiveSuit(it) !in voids.getValue(seat) }
            if (oi >= 0) {
                hand[i] = otherHand[oi]
                otherHand[oi] = outgoing
                return
            }
        }
    }

    /** Copies the public state across verbatim around the sampled [hands] and [kitty]. */
    private fun reconstruct(
        view: PlayerView,
        hands: Map<Seat, List<Card>>,
        kitty: List<Card>,
        random: Random,
    ): GameState = GameState(
        // Only consumed if a rollout deals the next hand; drawn from the injected Random so
        // sampled worlds stay reproducible under a fixed seed.
        rngSeed = random.nextLong(),
        handNumber = view.handNumber,
        dealer = view.dealer,
        phase = view.phase,
        teamCount = view.teamCount,
        hands = hands,
        kitty = kitty,
        bidding = BiddingState(
            history = view.biddingHistory,
            // Matches applyBid's accumulation: a seat is out of the auction iff it has passed.
            passed = view.biddingHistory.filter { it.second == Bid.Pass }.map { it.first }.toSet(),
            highBid = view.highBid,
            highBidder = view.highBidder,
            toAct = view.toAct ?: view.seat,
        ),
        contract = view.contract,
        activeSeats = view.activeSeats,
        leader = view.leader,
        currentTrick = view.currentTrick,
        ledSuit = view.ledSuit,
        trickNumber = view.trickNumber,
        tricksWon = view.tricksWon,
        lastTrick = view.lastTrick,
        scores = view.scores,
        lastHandResult = view.lastHandResult,
        handResults = view.handResults,
        winner = view.winner,
    )
}
