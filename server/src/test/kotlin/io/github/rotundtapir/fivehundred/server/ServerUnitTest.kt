// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.net.Distribution
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import io.github.rotundtapir.fivehundred.net.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUnitTest {

    @Test
    fun `bot names never collide with a seated human name, case-insensitively`() {
        // Every seed, every count: a human "jack" (or "JACK") means no bot may be named Jack.
        for (seed in 0L until 50L) {
            val picked = Room.pickBotNames(
                pool = Room.BOT_NAMES,
                taken = listOf("jack", "ALICE", "Mona"),
                count = 3,
                random = kotlin.random.Random(seed),
            )
            assertEquals(3, picked.size)
            assertEquals(3, picked.distinct().size, "bot names must be unique among themselves")
            assertTrue(picked.none { it.lowercase() in setOf("jack", "alice", "mona") }, "picked=$picked")
        }
    }

    @Test
    fun `version comparison handles differing segment counts`() {
        assertTrue(Versions.isAtLeast("0.3.0", "0.3.0"))
        assertTrue(Versions.isAtLeast("0.3.1", "0.3.0"))
        assertTrue(Versions.isAtLeast("1.0", "0.9.9"))
        assertFalse(Versions.isAtLeast("0.2.9", "0.3.0"))
        assertFalse(Versions.isAtLeast("0.3", "0.3.1"))
    }

    @Test
    fun `token bucket allows a burst then refills over time`() {
        var now = 0L
        val limiter = RateLimiter(ratePerSecond = 10, burst = 3) { now }
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire(), "burst exhausted")
        now += 100 // one token refills at 10/s
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `sliding window enforces a per-key limit`() {
        var now = 0L
        val counter = SlidingWindowCounter(windowMillis = 1000, limit = 2) { now }
        assertTrue(counter.tryRecord("a"))
        assertTrue(counter.tryRecord("a"))
        assertFalse(counter.tryRecord("a"))
        assertTrue(counter.tryRecord("b"), "different key has its own budget")
        now += 1001
        assertTrue(counter.tryRecord("a"), "window slid; budget restored")
    }

    @Test
    fun `sliding window evicts keys once their hits age out`() {
        var now = 0L
        val counter = SlidingWindowCounter(windowMillis = 1000, limit = 5) { now }
        counter.tryRecord("a")
        counter.tryRecord("b")
        assertEquals(2, counter.keyCount())
        now += 2000 // both keys' hits are now stale
        counter.evictStale()
        assertEquals(0, counter.keyCount(), "stale keys should be dropped by the sweep")
    }

    @Test
    fun `session registry binds, looks up, clears, and evicts by TTL`() {
        var now = 0L
        val registry = SessionRegistry(nowMillis = { now })
        val token = registry.newToken()
        registry.bind(token, "game-1", Seat(2))
        assertEquals("game-1", registry.lookup(token)?.gameId)
        assertEquals(Seat(2), registry.lookup(token)?.seat)

        registry.clear(token)
        assertNull(registry.lookup(token), "a cleared token is forgotten")

        val stale = registry.newToken()
        val fresh = registry.newToken()
        now += 1000
        registry.lookup(fresh) // refresh fresh's TTL
        now += 500
        registry.evictStale(ttlMillis = 1000)
        assertNull(registry.lookup(stale), "the untouched token is swept")
        assertEquals(1, registry.size(), "the recently-touched token survives")
    }

    @Test
    fun `per-IP connection cap admits up to the limit, refuses beyond, and frees on close`() {
        val server = GameServer(
            ServerConfig(devMode = false, maxConnectionsPerIp = 2),
            CoroutineScope(SupervisorJob()),
        )
        assertTrue(server.tryOpenConnection("1.2.3.4"))
        assertTrue(server.tryOpenConnection("1.2.3.4"))
        assertFalse(server.tryOpenConnection("1.2.3.4"), "third over the cap of 2 is refused")
        assertEquals(2, server.connectionsFor("1.2.3.4"), "a refused attempt is rolled back")
        server.closeConnection("1.2.3.4")
        assertTrue(server.tryOpenConnection("1.2.3.4"), "a freed slot admits a new connection")
        assertTrue(server.tryOpenConnection("5.6.7.8"), "a different IP has its own budget")
    }

    @Test
    fun `dev mode bypasses the per-IP connection cap`() {
        val server = GameServer(ServerConfig(devMode = true, maxConnectionsPerIp = 1), CoroutineScope(SupervisorJob()))
        repeat(10) { assertTrue(server.tryOpenConnection("1.2.3.4")) }
    }

    @Test
    fun `join codes use only unambiguous characters and are unique`() {
        // Each created room launches actor + idle-ticker coroutines on this scope; cancel it at the
        // end so they don't leak onto Dispatchers.Default and starve other tests in the same JVM.
        val scope = CoroutineScope(SupervisorJob())
        try {
            val registry = RoomRegistry(ServerConfig(devMode = true), scope, SessionRegistry(), Metrics(), AbuseLog())
            val codes = (1..200).mapNotNull {
                (registry.create("tok-$it", LobbyConfig(playerCount = 2, teamCount = 2), null)
                    as? RoomRegistry.CreateResult.Created)?.room?.joinCode
            }
            assertEquals(200, codes.size)
            assertEquals(codes.size, codes.toSet().size, "codes must be unique")
            val forbidden = Regex("[^23456789ABCDEFGHJKLMNPQRSTUVWXYZ]")
            codes.forEach { code ->
                assertEquals(4, code.length, "codes are 4 chars: $code")
                assertFalse(forbidden.containsMatchIn(code), "no uppercase-ambiguous glyphs (0/O/1/I) in $code")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `metrics render in Prometheus text format`() {
        val metrics = Metrics()
        metrics.connectionOpened()
        metrics.gameStarted()
        val text = metrics.render(roomsActive = 3, draining = true)
        assertTrue(text.contains("fivehundred_connections_total 1"))
        assertTrue(text.contains("fivehundred_rooms_active 3"))
        assertTrue(text.contains("fivehundred_draining 1"))
        assertTrue(text.contains("fivehundred_rejections_total{reason="))
    }

    @Test
    fun `per-build metrics admit only release-shaped version labels`() {
        val metrics = Metrics()
        metrics.connectAccepted(Platform.WEB, Distribution.WEB, "0.3.6")
        // Client-controlled junk — including an attempted forged journal/exposition line — never
        // becomes a label value; it is bucketed as "other".
        metrics.connectAccepted(Platform.ANDROID, Distribution.UNKNOWN, "999.0.0\nABUSE event=x ip=1.2.3.4")
        metrics.connectAccepted(Platform.ANDROID, Distribution.FOSS, "not-a-version")
        val text = metrics.render(roomsActive = 0, draining = false)
        assertTrue(
            text.contains("""fivehundred_connects_by_build_total{platform="web",flavor="web",version="0.3.6"} 1"""),
        )
        assertTrue(
            text.contains("""fivehundred_connects_by_build_total{platform="android",flavor="foss",version="other"} 1"""),
        )
        assertFalse(text.contains("ABUSE"), "raw client input must never reach the exposition")
    }

    @Test
    fun `per-build metrics cap distinct label sets`() {
        val metrics = Metrics()
        repeat(200) { metrics.connectAccepted(Platform.WEB, Distribution.WEB, "0.$it.0") }
        val labelled = metrics.render(roomsActive = 0, draining = false)
            .lines().filter { it.startsWith("fivehundred_connects_by_build_total{") }
        assertTrue(labelled.size <= 65, "cardinality must stay bounded; got ${labelled.size} label sets")
        assertTrue(labelled.any { it.contains("version=\"other\"") }, "overflow lands in the other bucket")
    }

    @Test
    fun `stats window reports aggregate deltas then resets`() {
        val metrics = Metrics()
        metrics.connectAccepted(Platform.WEB, Distribution.WEB, "0.3.6")
        metrics.connectAccepted(Platform.WEB, Distribution.WEB, "0.3.6")
        metrics.connectAccepted(Platform.ANDROID, Distribution.FOSS, "0.3.5")
        metrics.lobbyCreated()
        assertEquals(
            "stats window=1h connects=3 by-flavor foss=1 web=2 by-version 0.3.5=1 0.3.6=2 lobbies=1",
            metrics.drainStatsWindow(),
        )
        assertEquals("stats window=1h connects=0 lobbies=0", metrics.drainStatsWindow(), "window resets")
        metrics.connectAccepted(Platform.WEB, Distribution.WEB, "0.3.6")
        assertEquals(
            "stats window=1h connects=1 by-flavor web=1 by-version 0.3.6=1 lobbies=0",
            metrics.drainStatsWindow(),
            "only the new window's connects are reported",
        )
    }

    @Test
    fun `config reads from an injected environment`() {
        val env = mapOf(
            "PORT" to "9000",
            "DEV_MODE" to "true",
            "ALLOWED_ORIGINS" to "https://a.example, https://b.example",
            "MAX_CONNECTIONS_PER_IP" to "3",
            "LOBBY_GRACE_MILLIS" to "5000",
        )
        val config = ServerConfig.fromEnv { env[it] }
        assertEquals(9000, config.port)
        assertTrue(config.devMode)
        assertEquals(listOf("https://a.example", "https://b.example"), config.allowedOrigins)
        assertEquals(3, config.maxConnectionsPerIp)
        assertEquals(5000L, config.lobbyDisconnectGraceMillis)
    }

    @Test
    fun `origin allowlist and wildcard`() {
        val restricted = ServerConfig(allowedOrigins = listOf("https://rotundtapir.github.io"))
        assertTrue(restricted.originAllowed("https://rotundtapir.github.io"))
        assertTrue(restricted.originAllowed(null), "non-browser clients send no Origin")
        assertFalse(restricted.originAllowed("https://evil.example"))
        assertTrue(ServerConfig(allowedOrigins = listOf("*")).originAllowed("https://anything.example"))
    }
}
