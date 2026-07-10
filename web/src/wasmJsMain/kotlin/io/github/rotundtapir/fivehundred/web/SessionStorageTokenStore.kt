// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.web

import io.github.rotundtapir.fivehundred.online.SessionTokenStore
import kotlinx.browser.window

/**
 * [SessionTokenStore] backed by the tab's `sessionStorage`, so the online session survives a page
 * reload — including a same-tab navigation to an invite link, which is how a host "opens their own
 * link". Per-tab on purpose (not `localStorage`): a token shared across tabs would have each tab's
 * reconnect evict the other's socket in an endless loop over the one seat it owns.
 */
class SessionStorageTokenStore : SessionTokenStore {
    override suspend fun load(): String? = window.sessionStorage.getItem(KEY)

    override suspend fun save(token: String?) {
        if (token == null) window.sessionStorage.removeItem(KEY) else window.sessionStorage.setItem(KEY, token)
    }

    private companion object {
        const val KEY = "online.session_token"
    }
}
