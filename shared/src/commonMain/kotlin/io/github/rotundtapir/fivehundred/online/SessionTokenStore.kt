// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.online

/**
 * Persistence seam for the online session token, so a brand-new app/page instance can present the
 * token in its `Hello` and resume the seat it held (see [OnlineViewModel]). The web shell backs it
 * with the tab's `sessionStorage`: a page reload — including a same-tab navigation to an invite
 * link — starts a fresh wasm instance with no memory, and this is the only thing that lets it
 * reclaim its lobby seat instead of the room seeing a stranger. Deliberately NOT shared storage:
 * two tabs presenting one token would evict each other's socket in an endless reconnect loop.
 *
 * Android keeps [None]: its ViewModel (and so the in-memory token) already survives everything
 * short of process death, and the server's session TTL makes a much-later resume moot.
 */
interface SessionTokenStore {
    suspend fun load(): String?

    /** Persist [token]; null erases the stored value (the player abandoned the session's room). */
    suspend fun save(token: String?)

    companion object {
        /** No persistence — the token lives only as long as the ViewModel instance. */
        val None: SessionTokenStore = object : SessionTokenStore {
            override suspend fun load(): String? = null
            override suspend fun save(token: String?) = Unit
        }
    }
}
