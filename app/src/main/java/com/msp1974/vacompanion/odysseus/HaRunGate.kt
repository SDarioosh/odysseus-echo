package com.msp1974.vacompanion.odysseus

// Odysseus fork: never start an Assist run while Home Assistant's previous run is still going.
//
// Why: HA's Wyoming satellite (homeassistant/components/wyoming/assist_satellite.py) keeps one
// "pipeline running" flag per satellite and clears it on *any* run's RUN_END. A wake word that
// interrupts a turn which is already past speech-to-text (HA waiting for the conversation agent)
// used to start the new run at once; the old run then ended, cleared the flag for the new one,
// and HA dropped the new run's audio as "unexpected". Every later run was ended the same way, so
// the satellite stayed broken until it reconnected.
//
// So, after a barge-in:
//   - the new run waits until HA says the old one ended ("pipeline-ended", sent by the VACA
//     integration on every RUN_END); the audio said meanwhile is kept by DetectionBuffer;
//   - the old run's remaining events (its answer audio, transcript, pipeline-ended) are dropped,
//     so the interrupted answer is never played and can't end the new run;
//   - if HA hasn't ended the old run within MAX_WAIT_MS the new turn is given up, not started,
//     which keeps HA's state consistent.

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

object HaRunGate {
    /** DetectionBuffer keeps ~16 s (200 frames of 80 ms); stay inside it. */
    const val MAX_WAIT_MS = 15_000L

    /** A run HA never reported ended is treated as gone after this (longest pipeline timeout + margin). */
    private const val STALE_RUN_MS = 200_000L

    /** Old answer audio still streaming after its run ended is dropped for at most this long. */
    private const val OLD_AUDIO_GRACE_MS = 5_000L

    private val active = MutableStateFlow(false)
    @Volatile private var activeSince = 0L
    @Volatile private var suppressing = false
    @Volatile private var oldAudioOpen = false
    @Volatile private var oldRunEndedAt = 0L

    /** The satellite asked HA for a run, or HA started one (transcribe). */
    fun runStarted() {
        activeSince = System.currentTimeMillis()
        active.value = true
    }

    /** A wake word interrupted the current turn: drop what HA still sends for it. */
    fun interrupted() {
        if (isActive()) {
            suppressing = true
            oldAudioOpen = false
            oldRunEndedAt = 0L
            Timber.i("Barge-in: waiting for Home Assistant to end the interrupted run")
        }
    }

    /** New connection or satellite restart: HA has no run for us. */
    fun reset() {
        active.value = false
        suppressing = false
        oldAudioOpen = false
    }

    private fun isActive(): Boolean {
        if (active.value && System.currentTimeMillis() - activeSince > STALE_RUN_MS) {
            Timber.w("Assist run never reported ended; treating it as gone")
            active.value = false
        }
        return active.value
    }

    /** Waits until HA has no run in progress. False if it didn't end in time. */
    suspend fun awaitIdle(): Boolean {
        if (!isActive()) return true
        val ended = withTimeoutOrNull(MAX_WAIT_MS) { active.first { !it } } != null
        if (!ended) Timber.w("Barge-in: Home Assistant didn't end the interrupted run in ${MAX_WAIT_MS / 1000} s; not starting a new one")
        return ended
    }

    /**
     * Called for every packet from HA before it is routed. Returns true when the packet belongs to
     * an interrupted run and must be dropped.
     */
    fun onServerPacket(type: String): Boolean {
        if (type == "pipeline-ended") {
            val wasSuppressing = suppressing
            active.value = false
            if (wasSuppressing) {
                suppressing = false
                oldRunEndedAt = System.currentTimeMillis()
                Timber.i("Barge-in: interrupted run ended")
                return true
            }
            return false
        }
        if (type == "transcribe") {
            // HA started a run itself (e.g. a follow-up it asked for).
            if (!suppressing) runStarted()
            return suppressing
        }
        val isAudio = type == "audio-start" || type == "audio-chunk" || type == "audio-stop"
        if (suppressing) {
            if (type == "audio-start") oldAudioOpen = true
            if (type == "audio-stop") oldAudioOpen = false
            return true
        }
        // The interrupted answer's audio can still be streaming after its run ended.
        if (oldAudioOpen && isAudio) {
            if (System.currentTimeMillis() - oldRunEndedAt > OLD_AUDIO_GRACE_MS) {
                oldAudioOpen = false
                return false
            }
            if (type == "audio-stop") oldAudioOpen = false
            return true
        }
        return false
    }
}
