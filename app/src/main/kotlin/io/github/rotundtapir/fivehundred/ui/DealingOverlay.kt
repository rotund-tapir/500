// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.CardBack
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.fivehundred.AnimationSpeed
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The dealing animation for a new hand of 500: single card backs fly, one at a time, from a deck
 * in the centre of the felt to each destination in 500's packet order — 3 to each player then one
 * to the kitty, then 4 each + kitty, then 3 each + kitty. Opponents' piles grow in the opponents
 * row, the kitty pile grows on the felt, and the human's cards accumulate face down at the bottom
 * of the screen, then flip face up (with a small left-to-right stagger) when the deal completes.
 *
 * Everything here is presentational: the engine has already dealt, and the ViewModel holds the
 * first bidder for `dealPauseMillis`, so the whole animation (flights + flip) must finish inside
 * that budget. At [AnimationSpeed.OFF] none of this runs and [DealAnimationState.stage] stays
 * [DealStage.DONE].
 */
internal enum class DealStage { DEALING, FLIPPING, DONE }

/** A destination a dealt card can fly to. Also used as the anchor key for that destination. */
internal sealed interface DealTarget {
    data class SeatPile(val seat: Seat) : DealTarget
    data object Kitty : DealTarget
}

/** Anchor key for the deck the cards fly *from*. */
internal data object DeckAnchor

internal class DealAnimationState {
    var stage by mutableStateOf(DealStage.DONE)

    /** Cards landed so far, per destination. */
    val counts = mutableStateMapOf<DealTarget, Int>()

    /** Non-null while exactly one card back is in flight towards this target. */
    var flyingTarget by mutableStateOf<DealTarget?>(null)

    /** Centre of the in-flight card, in root coordinates. */
    val flyingPos = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Destination/deck centres in root coordinates, reported by [dealAnchor]. */
    val anchors = mutableStateMapOf<Any, Offset>()

    /** Root offset of the overlay Box the flying card is drawn in. */
    var overlayOrigin by mutableStateOf(Offset.Zero)

    val dealing: Boolean get() = stage != DealStage.DONE
    fun dealtTo(seat: Seat): Int = counts[DealTarget.SeatPile(seat)] ?: 0
    val kittyCount: Int get() = counts[DealTarget.Kitty] ?: 0
}

/** Reports this composable's centre (in root coordinates) as the anchor for [key]. */
internal fun Modifier.dealAnchor(state: DealAnimationState, key: Any): Modifier =
    onGloballyPositioned { coords ->
        state.anchors[key] =
            coords.positionInRoot() + Offset(coords.size.width / 2f, coords.size.height / 2f)
    }

/**
 * Per-speed budgets. The flights self-correct against a deadline (frame quantisation would
 * otherwise accumulate), and flight + flip must total under the ViewModel's deal hold with
 * ~200ms slack: SLOW 4200 → 3300+660=3960, NORMAL 2500 → 1750+492=2242, FAST 1200 → 700+275=975.
 */
internal data class DealTimings(val flyBudgetMillis: Long, val flipMillis: Int, val flipStaggerMillis: Int)

internal fun dealTimings(speed: AnimationSpeed): DealTimings = when (speed) {
    AnimationSpeed.SLOW -> DealTimings(flyBudgetMillis = 3_300, flipMillis = 300, flipStaggerMillis = 40)
    AnimationSpeed.FAST -> DealTimings(flyBudgetMillis = 700, flipMillis = 140, flipStaggerMillis = 15)
    else -> DealTimings(flyBudgetMillis = 1_750, flipMillis = 240, flipStaggerMillis = 28)
}

/** Flights shorter than this read as teleports anyway; skip the animation and just land the card. */
private const val MIN_FLIGHT_MILLIS = 8

/**
 * Drives one full deal. Suspends until the flip has finished; always leaves the state at
 * [DealStage.DONE] even if cancelled mid-deal.
 */
internal suspend fun runDealAnimation(
    state: DealAnimationState,
    playerCount: Int,
    dealer: Seat,
    speed: AnimationSpeed,
) {
    val timings = dealTimings(speed)
    val totalCards = playerCount * 10 + 3
    try {
        state.counts.clear()
        state.stage = DealStage.DEALING
        val startNanos = System.nanoTime()
        var flown = 0
        // Deal order: eldest hand (left of dealer) first, dealer last — packets of 3/4/3 per seat,
        // one kitty card after each full round of packets.
        val seats = (1..playerCount).map { Seat((dealer.index + it) % playerCount) }
        suspend fun fly(target: DealTarget) {
            val elapsed = (System.nanoTime() - startNanos) / 1_000_000
            val remaining = timings.flyBudgetMillis - elapsed
            val duration = (remaining / (totalCards - flown)).toInt()
            state.flyOne(target, duration)
            flown++
        }
        for (packet in intArrayOf(3, 4, 3)) {
            for (seat in seats) repeat(packet) { fly(DealTarget.SeatPile(seat)) }
            fly(DealTarget.Kitty)
        }
        state.stage = DealStage.FLIPPING
        delay(timings.flipMillis + timings.flipStaggerMillis * 9L + 30L)
    } finally {
        state.flyingTarget = null
        state.stage = DealStage.DONE
    }
}

private suspend fun DealAnimationState.flyOne(target: DealTarget, durationMillis: Int) {
    val from = awaitAnchor(DeckAnchor)
    val to = awaitAnchor(target)
    if (from != null && to != null && durationMillis >= MIN_FLIGHT_MILLIS) {
        flyingPos.snapTo(from)
        flyingTarget = target
        flyingPos.animateTo(to, tween(durationMillis, easing = FastOutSlowInEasing))
        flyingTarget = null
    }
    counts[target] = (counts[target] ?: 0) + 1
}

/** Waits (briefly) for an anchor to be laid out; null if it never appears, so the deal can't hang. */
private suspend fun DealAnimationState.awaitAnchor(key: Any): Offset? =
    anchors[key] ?: withTimeoutOrNull(500L) {
        snapshotFlow { anchors[key] }.filterNotNull().first()
    }

private val FlyingCardWidth = 44.dp

/** The single in-flight card back, drawn in a full-size overlay Box at root coordinates. */
@Composable
internal fun FlyingDealCard(state: DealAnimationState) {
    if (state.flyingTarget == null) return
    Box(
        Modifier.offset {
            val centre = state.flyingPos.value - state.overlayOrigin
            IntOffset(
                (centre.x - FlyingCardWidth.toPx() / 2f).roundToInt(),
                (centre.y - FlyingCardWidth.toPx() * 0.7f).roundToInt(),
            )
        },
    ) {
        CardBack(width = FlyingCardWidth)
    }
}

private val KittyCardWidth = 32.dp
private val KittyFanStep = 18.dp

/** A small fanned pile of [count] face-down cards labelled "Kitty"; holds its 3-card footprint. */
@Composable
internal fun KittyPile(count: Int, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box {
            Spacer(Modifier.size(KittyCardWidth + KittyFanStep * 2, KittyCardWidth * 1.4f))
            repeat(count) { i ->
                Box(Modifier.offset(x = KittyFanStep * i)) { CardBack(width = KittyCardWidth) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Kitty", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Felt centre while a hand is being dealt (and just after): the deck the cards fly out of, plus
 * the growing kitty pile. The deck header collapses once the last card lands, leaving the kitty
 * where the plain bidding-phase kitty renders.
 */
@Composable
internal fun DealFelt(state: DealAnimationState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = state.stage == DealStage.DEALING,
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Dealing…", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.dealAnchor(state, DeckAnchor)) {
                    repeat(3) { i ->
                        Box(Modifier.offset(x = 1.5.dp * i, y = 1.5.dp * i)) { CardBack(width = 48.dp) }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        KittyPile(count = state.kittyCount, modifier = Modifier.dealAnchor(state, DealTarget.Kitty))
    }
}

private val OpponentPileStep = 1.2.dp

/**
 * An opponent's card-back slot. Normally a single back; while dealing it's a pile that thickens
 * as each flown card lands (a faint outline marks the empty slot before the first card arrives).
 * Both forms occupy the same footprint so the row doesn't jump when the deal ends.
 */
@Composable
internal fun OpponentPile(seat: Seat, state: DealAnimationState, width: Dp) {
    Box(
        Modifier
            .size(width + OpponentPileStep * 4, width * 1.4f + OpponentPileStep * 4)
            .dealAnchor(state, DealTarget.SeatPile(seat)),
    ) {
        if (!state.dealing) {
            CardBack(width = width)
        } else {
            val landed = state.dealtTo(seat)
            if (landed == 0) Box(Modifier.alpha(0.25f)) { CardBack(width = width) }
            repeat(landed.coerceAtMost(5)) { i ->
                Box(Modifier.offset(x = OpponentPileStep * i, y = OpponentPileStep * i)) {
                    CardBack(width = width)
                }
            }
        }
    }
}

private val DealHandCardWidth = 64.dp

/**
 * The human's hand area while dealing/flipping: face-down backs accumulate as cards land, then
 * every card flips face up (Y-axis rotation, staggered left to right) revealing the same order
 * the interactive hand will render in. Replaces the ActionArea until the flip completes.
 */
@Composable
internal fun DealingHandRow(
    cards: List<Card>,
    state: DealAnimationState,
    humanSeat: Seat,
    timings: DealTimings,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("You", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .dealAnchor(state, DealTarget.SeatPile(humanSeat)),
            contentAlignment = Alignment.Center,
        ) {
            // Hold the row's height before the first card lands so the layout doesn't jump.
            Spacer(Modifier.height(DealHandCardWidth * 1.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(-DealHandCardWidth * 0.45f)) {
                if (state.stage == DealStage.FLIPPING) {
                    cards.forEachIndexed { i, card ->
                        FlippingCard(card, i, DealHandCardWidth, timings)
                    }
                } else {
                    repeat(state.dealtTo(humanSeat)) { CardBack(width = DealHandCardWidth) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One card of the reveal: back rotates 0→90°, then the face (pre-mirrored) carries 90°→180°. */
@Composable
private fun FlippingCard(card: Card, index: Int, width: Dp, timings: DealTimings) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * timings.flipStaggerMillis.toLong())
        rotation.animateTo(180f, tween(timings.flipMillis, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            rotationY = rotation.value
            cameraDistance = 8f * density
        },
    ) {
        if (rotation.value <= 90f) {
            CardBack(width = width)
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) { PlayingCard(card, width = width) }
        }
    }
}
