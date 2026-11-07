package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: what the Music page's tabs call to change what plays (docs/odysseus/music.md).
// Every action runs on a process-wide scope (it finishes even when the page goes away) against
// MusicRepository.backend; a failure becomes a short line the page shows for a few seconds
// (MusicActions.message): "Locked" while Lock mode is on, "Home Assistant offline" when it can't be
// reached, else the action's label and a short reason. Nothing here throws.

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.odysseus.ha.HaException
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.MusicBackend
import com.msp1974.vacompanion.odysseus.music.MusicLocked
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.QueueSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException

/** The text shown for MusicLocked. */
const val MUSIC_LOCKED = "Locked"

/** The text shown when Home Assistant can't be reached. */
const val MUSIC_HA_OFFLINE = "Home Assistant offline"

private const val MAX_REASON = 60

/**
 * What the page says when the action [label] (e.g. "Pause", "Move here") failed with [e]. Pure,
 * unit-tested (MusicActionsTest). Lock mode and an unreachable Home Assistant say only that.
 */
fun musicActionMessage(label: String, e: Throwable): String {
    val reason = when {
        e is MusicLocked -> return MUSIC_LOCKED
        e is IOException -> return MUSIC_HA_OFFLINE
        e is HaException && (e.code == 401 || e.code == 403) -> "Home Assistant refused (sign in again)"
        e is HaException && e.code >= 500 -> "Home Assistant error (HTTP ${e.code})"
        else -> e.message?.trim()?.takeIf { it.isNotEmpty() } ?: e.javaClass.simpleName
    }
    val short = if (reason.length > MAX_REASON) reason.take(MAX_REASON - 1).trimEnd() + "…" else reason
    return if (label.isBlank()) short else "$label: $short"
}

/**
 * True when [positionAtMs] is on the wall clock (System.currentTimeMillis: Home Assistant's
 * media_position_updated_at), false when it is on SystemClock.elapsedRealtime (Sendspin). Any time
 * after 2001 on the wall clock is larger than any uptime a device reaches. Pure.
 */
fun isWallClock(positionAtMs: Long): Boolean = positionAtMs >= 1_000_000_000_000L

object MusicActions {
    /** How long a message stays. */
    const val MESSAGE_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var messageSeq = 0

    /** The line the page shows now (an action failed), or null. Compose state. */
    var message by mutableStateOf<String?>(null)
        private set

    /** Shows [text] for [MESSAGE_MS] (replacing any message shown). */
    fun show(text: String) {
        val seq = synchronized(this) { ++messageSeq }
        message = text
        scope.launch {
            delay(MESSAGE_MS)
            if (synchronized(this@MusicActions) { messageSeq == seq }) message = null
        }
    }

    /**
     * Runs [block] with the backend on the process scope; a failure is shown as
     * [musicActionMessage]([label], e). Returns the job, or null without a backend.
     */
    fun run(label: String, block: suspend (MusicBackend) -> Unit): Job? {
        val backend = MusicRepository.backend
        if (backend == null) {
            show(musicActionMessage(label, IOException("no backend")))
            return null
        }
        return scope.launch {
            try {
                block(backend)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Music: %s failed: %s", label, e.message)
                show(musicActionMessage(label, e))
            }
        }
    }

    /** A player control on [roomId] (seek seconds, volume 0..1, shuffle Boolean, Repeat, mute Boolean, …). */
    fun control(roomId: String, control: Control, value: Any? = null): Job? =
        run(controlLabel(control)) { it.control(roomId, control, value) }

    /** Moves what plays in [fromRoomId] to [toRoomId] ("Move here"). */
    fun transfer(fromRoomId: String, toRoomId: String): Job? =
        run("Move") { it.transfer(fromRoomId, toRoomId) }

    /** Groups [memberIds] with [leaderId] (the leader's music plays in all). */
    fun group(leaderId: String, memberIds: List<String>): Job? =
        run("Group") { it.group(leaderId, memberIds) }

    /** Takes [roomId] out of its group. */
    fun ungroup(roomId: String): Job? =
        run("Ungroup") { it.ungroup(roomId) }

    /** Shows [roomId] on the page (remembered as the last room). */
    fun select(roomId: String) = MusicRepository.select(roomId)

    /** The queue summary of [roomId]; null when it has none, there's no backend or it failed. */
    suspend fun queue(roomId: String): QueueSummary? {
        val backend = MusicRepository.backend ?: return null
        return try {
            backend.queue(roomId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Music: queue of %s failed: %s", roomId, e.message)
            null
        }
    }

    /**
     * The clock [state]'s positionAtMs is on, for FullPlayer / Progress(clock = …): the wall
     * clock for Home Assistant's states (every state from MusicBackend.player and
     * rememberPlayerState), elapsedRealtime for Sendspin's own (toPlayerState()).
     */
    fun clockFor(state: PlayerState?): () -> Long =
        if (state == null || isWallClock(state.positionAtMs)) WALL_CLOCK else ELAPSED_CLOCK

    private val WALL_CLOCK: () -> Long = { System.currentTimeMillis() }
    private val ELAPSED_CLOCK: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    private fun controlLabel(control: Control): String = when (control) {
        Control.PLAY -> "Play"
        Control.PAUSE -> "Pause"
        Control.NEXT -> "Next"
        Control.PREVIOUS -> "Previous"
        Control.SEEK -> "Seek"
        Control.SHUFFLE -> "Shuffle"
        Control.REPEAT -> "Repeat"
        Control.VOLUME -> "Volume"
        Control.MUTE -> "Mute"
        Control.GROUP -> "Group"
    }
}
