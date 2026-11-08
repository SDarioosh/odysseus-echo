package com.msp1974.vacompanion.odysseus.player

// Odysseus fork: the one time base of PlayerState.positionAtMs: the wall clock
// (System.currentTimeMillis), as Home Assistant's media_position_updated_at is. Sendspin reports
// positions on SystemClock.elapsedRealtime; they are converted here, at the two boundaries
// (SendspinNowPlaying.toPlayerState, and PlayerState.toSendspinNowPlaying for the upstream
// now-playing card), so every PlayerState is drawn against [now].

import android.os.SystemClock
import kotlin.math.abs

object PlayerClock {
    /** The clock every PlayerState's positionAtMs is on. */
    val now: () -> Long = { System.currentTimeMillis() }

    /** A wall clock that moved further than this from elapsedRealtime (set by hand, NTP) is taken anew. */
    private const val DRIFT_MS = 1_000L

    @Volatile private var offset: Long? = null

    /**
     * Wall clock minus elapsedRealtime. Kept while it stays within [DRIFT_MS] of a fresh reading, so
     * converting the same position twice gives the same number (states stay equal, nothing is
     * redrawn for a millisecond of jitter).
     */
    fun wallMinusElapsed(): Long {
        val fresh = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val kept = offset
        if (kept != null && abs(fresh - kept) <= DRIFT_MS) return kept
        offset = fresh
        return fresh
    }

    fun elapsedToWall(elapsedMs: Long, offsetMs: Long = wallMinusElapsed()): Long = elapsedMs + offsetMs
    fun wallToElapsed(wallMs: Long, offsetMs: Long = wallMinusElapsed()): Long = wallMs - offsetMs
}
