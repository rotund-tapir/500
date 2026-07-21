// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.net.Distribution
import io.github.rotundtapir.fivehundred.net.ErrorCode
import io.github.rotundtapir.fivehundred.net.Platform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Hand-rolled counters rendered as Prometheus text exposition. Deliberately dependency-free (no
 * Micrometer) — at this scale a monitoring stack would consume more of the 1 GB box than it is
 * worth. Read ad-hoc over SSH via `docker compose exec`; `/metrics` is blocked at the proxy.
 */
class Metrics {
    /** One label set per client build. Platform/flavor are enums; version is validated, see [connectAccepted]. */
    private data class BuildKey(val platform: String, val flavor: String, val version: String)

    private val connectionsTotal = AtomicLong()
    private val connectionsActive = AtomicLong()
    private val gamesStarted = AtomicLong()
    private val gamesCompleted = AtomicLong()
    private val messagesTotal = AtomicLong()
    private val lobbiesCreated = AtomicLong()
    private val connectsByBuild = ConcurrentHashMap<BuildKey, AtomicLong>()
    private val rejections = ErrorCode.entries.associateWith { AtomicLong() }

    // Counter values at the last stats drain, so each window reports deltas. Only the stats
    // ticker touches these (single coroutine) — see [drainStatsWindow].
    private var drainedBuildCounts: Map<BuildKey, Long> = emptyMap()
    private var drainedLobbies = 0L

    fun connectionOpened() {
        connectionsTotal.incrementAndGet()
        connectionsActive.incrementAndGet()
    }

    fun connectionClosed() {
        connectionsActive.decrementAndGet()
    }

    fun gameStarted() {
        gamesStarted.incrementAndGet()
    }

    fun gameCompleted() {
        gamesCompleted.incrementAndGet()
    }

    fun messageReceived() {
        messagesTotal.incrementAndGet()
    }

    fun rejected(code: ErrorCode) {
        rejections.getValue(code).incrementAndGet()
    }

    fun lobbyCreated() {
        lobbiesCreated.incrementAndGet()
    }

    /**
     * Count an accepted [io.github.rotundtapir.fivehundred.net.Hello] by client build — anonymous
     * aggregates only, never a per-person record. The version label is client-controlled, so it is
     * admitted only when it looks like a release version (`major.minor[.patch]`; anything else
     * counts as "other"), and the number of distinct label sets is capped so a hostile client
     * can't explode metric cardinality. Platform and flavor are enums, bounded by construction.
     */
    fun connectAccepted(platform: Platform, flavor: Distribution, appVersion: String) {
        val version = appVersion
            .takeIf { it.length <= VERSION_LABEL_MAX_LENGTH && VERSION_LABEL.matches(it) }
            ?: OTHER_VERSION
        var key = BuildKey(platform.name.lowercase(), flavor.name.lowercase(), version)
        if (connectsByBuild.size >= MAX_BUILD_LABEL_SETS && !connectsByBuild.containsKey(key)) {
            key = key.copy(version = OTHER_VERSION)
        }
        connectsByBuild.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }

    /**
     * Render one aggregate summary line for the connects/lobbies since the previous call, and
     * advance the window. This is the journald history of who plays what — anonymous counts only,
     * which is exactly what keeps long-term retention out of privacy-policy territory. Call from a
     * single coroutine (the hourly stats ticker); not re-entrant.
     */
    fun drainStatsWindow(): String {
        val buildCounts = connectsByBuild.mapValues { (_, count) -> count.get() }
        val lobbiesTotal = lobbiesCreated.get()
        val connects = buildCounts
            .mapValues { (key, total) -> total - (drainedBuildCounts[key] ?: 0L) }
            .filterValues { it > 0 }
        val lobbies = lobbiesTotal - drainedLobbies
        drainedBuildCounts = buildCounts
        drainedLobbies = lobbiesTotal
        fun StringBuilder.appendGroup(label: String, dimension: (BuildKey) -> String) {
            val grouped = connects.entries
                .groupBy({ dimension(it.key) }, { it.value })
                .mapValues { (_, counts) -> counts.sum() }
                .toSortedMap()
            if (grouped.isEmpty()) return
            append(' ').append(label)
            for ((name, count) in grouped) append(' ').append(name).append('=').append(count)
        }
        return buildString {
            append("stats window=1h connects=").append(connects.values.sum())
            appendGroup("by-flavor", BuildKey::flavor)
            appendGroup("by-version", BuildKey::version)
            append(" lobbies=").append(lobbies)
        }
    }

    /** Render the current values in Prometheus text format. [roomsActive]/[draining] are pulled live. */
    fun render(roomsActive: Int, draining: Boolean): String = buildString {
        fun counter(name: String, help: String, value: Long) {
            append("# HELP ").append(name).append(' ').append(help).append('\n')
            append("# TYPE ").append(name).append(" counter\n")
            append(name).append(' ').append(value).append('\n')
        }
        fun gauge(name: String, help: String, value: Long) {
            append("# HELP ").append(name).append(' ').append(help).append('\n')
            append("# TYPE ").append(name).append(" gauge\n")
            append(name).append(' ').append(value).append('\n')
        }
        counter("fivehundred_connections_total", "WebSocket connections opened", connectionsTotal.get())
        gauge("fivehundred_connections_active", "Currently open connections", connectionsActive.get())
        gauge("fivehundred_rooms_active", "Currently live rooms", roomsActive.toLong())
        counter("fivehundred_games_started_total", "Games started", gamesStarted.get())
        counter("fivehundred_games_completed_total", "Games completed", gamesCompleted.get())
        counter("fivehundred_messages_total", "Client messages received", messagesTotal.get())
        counter("fivehundred_lobbies_created_total", "Lobbies created", lobbiesCreated.get())
        append("# HELP fivehundred_connects_by_build_total Accepted hellos by client platform/flavor/version\n")
        append("# TYPE fivehundred_connects_by_build_total counter\n")
        for ((key, count) in connectsByBuild.entries.sortedBy { (k, _) -> "${k.platform}/${k.flavor}/${k.version}" }) {
            append("fivehundred_connects_by_build_total{platform=\"").append(key.platform)
                .append("\",flavor=\"").append(key.flavor)
                .append("\",version=\"").append(key.version)
                .append("\"} ").append(count.get()).append('\n')
        }
        gauge("fivehundred_draining", "1 when the server is draining for restart", if (draining) 1 else 0)
        append("# HELP fivehundred_rejections_total Rejected requests by reason\n")
        append("# TYPE fivehundred_rejections_total counter\n")
        for ((code, count) in rejections) {
            append("fivehundred_rejections_total{reason=\"")
                .append(code.name.lowercase()).append("\"} ").append(count.get()).append('\n')
        }
    }

    private companion object {
        /** What a genuine release version looks like; anything else becomes [OTHER_VERSION]. */
        val VERSION_LABEL = Regex("""\d+\.\d+(\.\d+)?""")
        const val VERSION_LABEL_MAX_LENGTH = 24
        const val OTHER_VERSION = "other"
        const val MAX_BUILD_LABEL_SETS = 64
    }
}
