// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import io.github.rotundtapir.fivehundred.net.RoomPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Everything a restarted server needs to resurrect one room: the identity (game id, join code,
 * creator token), the lobby config, the per-seat roster with its session-token ownership, and — for
 * a game in progress — the authoritative engine state. The engine is a pure, seed-deterministic
 * state machine, so [gameState] alone fully restores a game; the [GameDriver] resumes from it.
 *
 * This is full hidden information (every hand, the kitty, and the bearer tokens that own the
 * seats). It exists only on the server's disk and must never be sent to a client.
 */
@Serializable
data class RoomSnapshot(
    /**
     * Schema version of this snapshot, checked at restore. Deliberately has no default: writers
     * must state it, and a pre-versioning file fails to decode instead of masquerading as v1. Bump
     * [CURRENT_VERSION] on any incompatible change to this class, [GameState], or the engine's
     * interpretation of a state — a mismatched snapshot is dropped at boot (that room degrades to
     * the pre-persistence behaviour: the game is lost, the server is fine).
     */
    val snapshotVersion: Int,
    val gameId: String,
    val joinCode: String,
    val creatorToken: String,
    val lobbyConfig: LobbyConfig,
    val phase: RoomPhase,
    val seats: List<SeatSnapshot>,
    /** Carried across the restart so post-restore versions keep increasing and never repeat. */
    val stateVersion: Int,
    /** The live engine state; non-null exactly while the room is [RoomPhase.PLAYING]. */
    val gameState: GameState?,
    val savedAtMillis: Long,
) {
    @Serializable
    data class SeatSnapshot(
        val name: String?,
        val isBot: Boolean,
        val ownerToken: String?,
    )

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Where room snapshots live between restarts. [save]/[delete] must never block the room actor —
 * implementations do their I/O elsewhere. [loadAll] runs once at boot, before the server accepts
 * connections.
 */
interface RoomPersistence {
    /** True when snapshots survive a process restart — gates the shutdown behaviour of rooms. */
    val durable: Boolean

    fun save(snapshot: RoomSnapshot)
    fun delete(gameId: String)
    fun loadAll(): List<RoomSnapshot>

    /**
     * Write out everything still queued, on the calling thread. Called once during graceful
     * shutdown so the newest state of every room reaches disk before the process exits; a hard
     * crash can still lose the last write or two, which restores as a state a move or so older.
     */
    fun flushSync() {}

    /** In-memory-only mode (no `DATA_DIR`): every operation is a no-op. */
    object None : RoomPersistence {
        override val durable: Boolean get() = false
        override fun save(snapshot: RoomSnapshot) = Unit
        override fun delete(gameId: String) = Unit
        override fun loadAll(): List<RoomSnapshot> = emptyList()
    }
}

/**
 * One JSON file per room under [dir], named `<gameId>.json`. Writes are handed to a single writer
 * coroutine on [Dispatchers.IO] and conflated per room — only the newest pending snapshot of a room
 * is ever written, so a bot-speed burst of state changes costs one file write, and the room actor
 * never waits on the disk. Each write goes to a temp file first and is atomically renamed into
 * place, so a crash mid-write can never corrupt an existing snapshot.
 */
class FileRoomPersistence(
    private val dir: Path,
    scope: CoroutineScope,
) : RoomPersistence {
    private val logger = LoggerFactory.getLogger("persistence")

    override val durable: Boolean get() = true

    /** Newest pending state per room; a [Tombstone] means "delete the file". */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val dirty = Channel<String>(Channel.UNLIMITED)

    private object Tombstone

    init {
        Files.createDirectories(dir)
        scope.launch(Dispatchers.IO) {
            for (gameId in dirty) {
                // Removing the entry claims this room's newest pending work; a duplicate id left in
                // the channel by an older save finds nothing and is skipped.
                when (val work = pending.remove(gameId)) {
                    null -> Unit
                    Tombstone -> runCatching { Files.deleteIfExists(fileFor(gameId)) }
                        .onFailure { logger.warn("failed to delete snapshot $gameId", it) }
                    is RoomSnapshot -> write(work)
                }
            }
        }
    }

    override fun save(snapshot: RoomSnapshot) {
        pending[snapshot.gameId] = snapshot
        dirty.trySend(snapshot.gameId)
    }

    override fun delete(gameId: String) {
        pending[gameId] = Tombstone
        dirty.trySend(gameId)
    }

    override fun flushSync() {
        // remove() is atomic, so racing the writer coroutine is safe: whoever claims an entry
        // writes it, the other finds nothing.
        for (gameId in pending.keys.toList()) {
            when (val work = pending.remove(gameId)) {
                null -> Unit
                Tombstone -> runCatching { Files.deleteIfExists(fileFor(gameId)) }
                is RoomSnapshot -> write(work)
            }
        }
    }

    override fun loadAll(): List<RoomSnapshot> = dir.listDirectoryEntries()
        .filter { it.extension == "json" }
        .mapNotNull { file ->
            runCatching { json.decodeFromString<RoomSnapshot>(file.readText()) }
                .onFailure {
                    // An unreadable snapshot (truncated disk, format change) loses one room, not the
                    // boot: log it, move it aside so it isn't retried forever, and carry on.
                    logger.warn("skipping unreadable snapshot ${file.fileName}", it)
                    runCatching {
                        Files.move(file, file.resolveSibling("${file.nameWithoutExtension}.corrupt"))
                    }
                }
                .getOrNull()
        }

    private fun write(snapshot: RoomSnapshot) {
        try {
            val target = fileFor(snapshot.gameId)
            val tmp = target.resolveSibling("${snapshot.gameId}.tmp")
            tmp.writeText(json.encodeToString(RoomSnapshot.serializer(), snapshot))
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            // A failed write costs restart durability for one room, never the live game itself.
            logger.warn("failed to write snapshot ${snapshot.gameId}", e)
        }
    }

    private fun fileFor(gameId: String): Path = dir.resolve("$gameId.json")

    private companion object {
        /** Lenient on read so an older server can still load snapshots written by a newer one. */
        val json = Json { ignoreUnknownKeys = true }
    }
}
