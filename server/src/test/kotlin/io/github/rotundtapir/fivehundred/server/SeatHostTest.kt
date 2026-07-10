// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Strategy
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.net.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deterministic unit tests for the seat's decision logic: bot fallback for empty/dropped seats,
 * turn-timeout fallback that keeps the seat, reclaim when a human (re)connects, disconnect interrupt,
 * and — the important safety property — that a stale buffered action is drained rather than replayed
 * on a later turn. Uses virtual time so nothing actually sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeatHostTest {

    private val botAction: Action = Action.PlaceBid(Bid.Pass)
    private val humanAction: Action = Action.PlaceBid(Bid.Pass)
    private val bot = Strategy<PlayerView, Action> { _, _ -> botAction }
    private val view: PlayerView = FiveHundredRules(playerCount = 2, teamCount = 2).let { it.view(it.newGame(1), Seat(0)) }

    private fun host() = SeatHost(Seat(0), bot, Random(1), turnTimeout = 1000.milliseconds)

    private fun connection() = PlayerConnection(
        id = 1,
        sessionToken = "tok",
        remoteIp = "1.2.3.4",
        platform = Platform.WEB,
        appVersion = "0.3.0",
        requestClose = {},
    )

    @Test
    fun `an empty seat is played by the bot`() = runTest {
        val host = host().apply { permanentBot = true }
        assertEquals(botAction, host.decide(view))
    }

    @Test
    fun `a seat whose human dropped is played by the bot`() = runTest {
        val host = host() // occupant left null == dropped
        assertEquals(botAction, host.decide(view))
    }

    @Test
    fun `a connected human's submitted action is returned`() = runTest {
        val host = host().apply { occupant = connection() }
        var result: Action? = null
        val job = launch { result = host.decide(view) }
        runCurrent()
        assertTrue(host.submit(humanAction), "submit should reach the parked decide")
        advanceUntilIdle()
        assertEquals(humanAction, result)
        job.join()
    }

    @Test
    fun `an unanswered turn falls back to the bot but keeps the seat`() = runTest {
        val host = host().apply { occupant = connection() }
        var result: Action? = null
        val job = launch { result = host.decide(view) }
        advanceTimeBy(1001) // run out the 1s clock without submitting
        advanceUntilIdle()
        assertEquals(botAction, result, "the bot covers the timed-out turn")
        // The seat is NOT surrendered: the next turn still accepts the human's action.
        var next: Action? = null
        val job2 = launch { next = host.decide(view) }
        runCurrent()
        assertTrue(host.submit(humanAction))
        advanceUntilIdle()
        assertEquals(humanAction, next, "control returns to the human on the next turn")
        job.join(); job2.join()
    }

    @Test
    fun `a disconnect interrupt makes the current turn fall back to the bot at once`() = runTest {
        val host = host().apply { occupant = connection() }
        var result: Action? = null
        val job = launch { result = host.decide(view) }
        runCurrent()
        host.interrupt() // occupant dropped mid-turn
        advanceUntilIdle()
        assertEquals(botAction, result, "interrupt hands the turn to the bot without waiting the clock")
        job.join()
    }

    @Test
    fun `a stale action buffered between turns is drained, never replayed on a later turn`() = runTest {
        val host = host().apply { occupant = connection() }
        // No decide() is parked, so this action just sits in the capacity-1 buffer (a stale submit
        // that raced the room's bookkeeping). Use a distinct action so a replay would be detectable.
        val staleAction = Action.PlaceBid(Bid.Misere)
        assertTrue(host.submit(staleAction))
        // The next turn must NOT consume that buffered action — decide() drains it first, then waits.
        var result: Action? = null
        val job = launch { result = host.decide(view) }
        runCurrent() // let decide() run its drain + park, WITHOUT advancing past the turn timeout
        assertFalse(job.isCompleted, "decide must still be waiting, not have consumed the stale action")
        host.submit(humanAction) // the real action for this turn
        runCurrent()
        assertEquals(humanAction, result, "the turn is answered by the fresh action, not the stale one")
        job.join()
    }
}
