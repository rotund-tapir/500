// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Opaque session tokens issued at [Hello] and their current room+seat binding. A reconnecting client
 * re-presents its token to reclaim its seat. Tokens are unguessable (16 random bytes) so they double
 * as bearer credentials for a seat — no accounts, no passwords.
 *
 * Bindings are bounded two ways so the map can't grow without limit on a long-lived process: they are
 * [clear]ed when a seat is vacated cleanly (leave/disband), and any binding untouched for longer than
 * the TTL is [evictStale]d by a periodic sweep. A live game refreshes its seated tokens each time a
 * client (re)connects, so an in-progress match never has its token swept out from under it.
 */
class SessionRegistry(
    private val random: SecureRandom = SecureRandom(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** A token's current placement. Both null until the client sits in a room. */
    class Binding(@Volatile var touchedAt: Long) {
        @Volatile var gameId: String? = null
        @Volatile var seat: Seat? = null
    }

    private val bindings = ConcurrentHashMap<String, Binding>()

    /** Mint a fresh, unbound token. */
    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        bindings[token] = Binding(nowMillis())
        return token
    }

    /** Record where [token]'s owner is now seated. */
    fun bind(token: String, gameId: String, seat: Seat) {
        bindings.getOrPut(token) { Binding(nowMillis()) }.apply {
            this.gameId = gameId
            this.seat = seat
            this.touchedAt = nowMillis()
        }
    }

    /** The current binding for [token], or null if the token is unknown. Refreshes its TTL. */
    fun lookup(token: String): Binding? = bindings[token]?.also { it.touchedAt = nowMillis() }

    /** Forget [token] (e.g. its room disbanded or it left cleanly). */
    fun clear(token: String) {
        bindings.remove(token)
    }

    /** Drop bindings untouched for at least [ttlMillis]; returns how many were removed. */
    fun evictStale(ttlMillis: Long): Int {
        val cutoff = nowMillis() - ttlMillis
        val stale = bindings.entries.filter { it.value.touchedAt < cutoff }
        stale.forEach { bindings.remove(it.key, it.value) }
        return stale.size
    }

    /** Current binding count — for tests and metrics. */
    fun size(): Int = bindings.size

    private companion object {
        const val TOKEN_BYTES = 16
    }
}
