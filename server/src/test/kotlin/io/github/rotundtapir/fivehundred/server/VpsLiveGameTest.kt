// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
// TEMPORARY live smoke test against a real deployed server. Not for CI: only runs when
// -Dvps.url=wss://host/ws is passed. Delete after the deployment review.
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.Emote
import io.github.rotundtapir.fivehundred.net.EmoteReceived
import io.github.rotundtapir.fivehundred.net.ErrorMessage
import io.github.rotundtapir.fivehundred.net.GameOver
import io.github.rotundtapir.fivehundred.net.Hello
import io.github.rotundtapir.fivehundred.net.JoinLobby
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.PROTOCOL_VERSION
import io.github.rotundtapir.fivehundred.net.Platform
import io.github.rotundtapir.fivehundred.net.SendEmote
import io.github.rotundtapir.fivehundred.net.ServerMessage
import io.github.rotundtapir.fivehundred.net.SetReady
import io.github.rotundtapir.fivehundred.net.StartGame
import io.github.rotundtapir.fivehundred.net.SubmitAction
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import io.github.rotundtapir.fivehundred.net.Welcome
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledIfSystemProperty(named = "vps.url", matches = ".+")
class VpsLiveGameTest {

    private val url: String get() = System.getProperty("vps.url")
    private fun newClient() = HttpClient(CIO) { install(WebSockets) }

    @Test
    fun `two real clients play a full 4p game on the VPS`() = runBlocking {
        val code = CompletableDeferred<String>()
        val joinerOver = CompletableDeferred<GameOver>()
        val emoteSeen = CompletableDeferred<EmoteReceived>()
        val creatorOverDeferred = CompletableDeferred<GameOver>()
        coroutineScope {
            launch {
                newClient().use { c ->
                    c.webSocket(url) {
                        sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                        waitFor<Welcome>()
                        sendMsg(JoinLobby(code.await(), "LiveBob"))
                        waitFor<LobbyState>()
                        sendMsg(SetReady(true))
                        sendMsg(SendEmote(Emote.GOOD_GAME))
                        val over = withTimeout(TIMEOUT) {
                            playLoggingEmotes(seed = 2, emoteSeen = null)
                        }
                        joinerOver.complete(over)
                    }
                }
            }
            newClient().use { c ->
                c.webSocket(url) {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID))
                    waitFor<Welcome>()
                    sendMsg(CreateLobby("LiveAlice", playerCount = 4, teamCount = 2))
                    code.complete(waitFor<LobbyState>().joinCode)
                    sendMsg(SetReady(true))
                    waitForLobby { st ->
                        val humans = st.seats.filter { !it.isBot && it.connected }
                        humans.size == 2 && humans.all { it.ready }
                    }
                    sendMsg(StartGame)
                    creatorOverDeferred.complete(
                        withTimeout(TIMEOUT) { playLoggingEmotes(seed = 1, emoteSeen = emoteSeen) },
                    )
                }
            }
        }
        val jo = joinerOver.await()
        val creatorOver = creatorOverDeferred.await()
        assertEquals(creatorOver.winnerTeam, jo.winnerTeam, "clients disagree on the winner")
        assertEquals(creatorOver.scores, jo.scores, "clients disagree on the final scores")
        assertTrue(emoteSeen.isCompleted, "creator never received the joiner's emote")
        assertEquals(Emote.GOOD_GAME, emoteSeen.await().emote)
        println("VPS GAME OK winner=${creatorOver.winnerTeam} scores=${creatorOver.scores}")
    }

    @Test
    fun `reconnect with token resumes a live VPS game`() = runBlocking {
        val client = newClient()
        var token: String? = null
        client.webSocket(url) {
            sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
            token = waitFor<Welcome>().sessionToken
            sendMsg(CreateLobby("LiveCarol", playerCount = 2, teamCount = 2))
            val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
            sendMsg(SetReady(true))
            waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
            sendMsg(StartGame)
            waitFor<ViewUpdate>() // game underway; drop the socket abruptly
        }
        client.webSocket(url) {
            sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = token))
            val welcome = waitFor<Welcome>()
            assertNotNull(welcome.resumed, "expected to resume into the live game")
            // Prove the reclaimed seat is actually playable: finish the game from here.
            val over = withTimeout(TIMEOUT) { playLoggingEmotes(seed = 3, emoteSeen = null) }
            assertTrue(over.winnerTeam in 0..1)
            println("VPS RESUME OK winner=${over.winnerTeam}")
        }
        client.close()
    }

    @Test
    fun `duplicate submit is tolerated on the VPS`() = runBlocking {
        newClient().use { c ->
            c.webSocket(url) {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                sendMsg(CreateLobby("LiveDave", playerCount = 2, teamCount = 2))
                val mySeat = assertNotNull(waitFor<LobbyState>().yourSeat)
                sendMsg(SetReady(true))
                waitForLobby { st -> st.seats.first { it.seat == mySeat }.ready }
                sendMsg(StartGame)
                val bot = FiveHundredBot()
                val rng = Random(4)
                var duplicated = false
                val over = withTimeout(TIMEOUT) {
                    var result: GameOver? = null
                    while (result == null) {
                        when (val m = nextMsg()) {
                            is GameOver -> result = m
                            is ViewUpdate -> if (m.view.isMyTurn) {
                                val action = bot.decide(m.view, rng)
                                delay(ACTION_PACE_MS)
                                sendMsg(SubmitAction(m.stateVersion, action))
                                if (!duplicated) { // double-send the very first action
                                    sendMsg(SubmitAction(m.stateVersion, action))
                                    duplicated = true
                                }
                            }
                            is ErrorMessage -> error("server rejected: $m")
                            else -> Unit
                        }
                    }
                    result
                }
                assertTrue(duplicated)
                println("VPS DUP-SUBMIT OK winner=${over.winnerTeam}")
            }
        }
    }

    @Test
    fun `origin gate admits the production web origin and refuses others`() = runBlocking {
        newClient().use { c ->
            c.webSocket(url, request = { header("Origin", "https://rotundtapir.github.io") }) {
                sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                waitFor<Welcome>()
                println("VPS ORIGIN-ALLOWED OK")
            }
            val refused = runCatching {
                c.webSocket(url, request = { header("Origin", "https://evil.example") }) {
                    sendMsg(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB))
                    withTimeout(10_000L) { waitFor<Welcome>() }
                }
            }
            assertTrue(refused.isFailure, "a disallowed Origin must not be welcomed")
            println("VPS ORIGIN-REFUSED OK")
        }
    }

    /** Like playWithBotUntilGameOver but also records the first emote seen. */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.playLoggingEmotes(
        seed: Long,
        emoteSeen: CompletableDeferred<EmoteReceived>?,
    ): GameOver {
        val bot = FiveHundredBot()
        val rng = Random(seed)
        while (true) {
            when (val message: ServerMessage = nextMsg()) {
                is GameOver -> return message
                is EmoteReceived -> emoteSeen?.complete(message)
                is ViewUpdate -> if (message.view.isMyTurn) {
                    delay(ACTION_PACE_MS) // stay under the production 10 msg/s limiter
                    sendMsg(SubmitAction(message.stateVersion, bot.decide(message.view, rng)))
                }
                is ErrorMessage -> error("server rejected a move mid-game: $message")
                else -> Unit
            }
        }
    }

    private companion object {
        const val TIMEOUT = 600_000L
        const val ACTION_PACE_MS = 150L
    }
}
