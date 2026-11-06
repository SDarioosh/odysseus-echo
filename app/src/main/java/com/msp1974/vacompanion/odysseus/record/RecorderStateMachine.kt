package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's gestures, the same as the T-Watch Ultra's (docs/odysseus/recording.md).
// Plain Kotlin, no Android: RecorderController feeds it presses, holds, a clock tick and "the
// microphone was muted", and carries out the effects it returns.
//
//   press  idle       -> start a recording
//          recording  -> stop it
//          note       -> stop the note (and go back to recording if the note interrupted one)
//   hold   idle       -> start a voice note (letting go does nothing)
//          recording  -> end the recording session, start a note; a new recording starts after it
//          note       -> stop the note (as press)
//   A note stops by itself after 5 min, a recording after 4 h. A gesture within 1 s of the last
//   one that did something is ignored. Nothing starts while the microphone is muted.

enum class RecordingKind { RECORDING, NOTE }

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val startedAtMs: Long) : RecorderState
    /** [resumeRecording]: the note interrupted a recording, which starts again after it. */
    data class Note(val startedAtMs: Long, val resumeRecording: Boolean) : RecorderState
}

sealed interface RecorderEffect {
    /** Open a new upload session and start capturing. [quiet]: no start sound (a recording resumed after a note). */
    data class StartSession(val kind: RecordingKind, val quiet: Boolean = false) : RecorderEffect
    /** Finish the current session: last chunk, then `/end`. */
    data class EndSession(val kind: RecordingKind) : RecorderEffect
    data object PlayStartSound : RecorderEffect
    data object PlayStopSound : RecorderEffect
    /** Nothing started; tell the person why. */
    data class Refused(val reason: String) : RecorderEffect
}

class RecorderStateMachine(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxNoteMs: Long = MAX_NOTE_MS,
    private val maxRecordingMs: Long = MAX_RECORDING_MS,
) {
    var state: RecorderState = RecorderState.Idle
        private set

    private var lastGestureMs: Long? = null

    /**
     * A short press at [nowMs]. [refusal] is why nothing may start now (muted, recording off,
     * not linked), or null; it only matters when the press would start something.
     */
    fun press(nowMs: Long, refusal: String? = null): List<RecorderEffect> {
        if (debounced(nowMs)) return emptyList()
        return when (val s = state) {
            RecorderState.Idle -> startRecording(nowMs, refusal)
            is RecorderState.Recording -> accept(nowMs) { stopRecording() }
            is RecorderState.Note -> accept(nowMs) { stopNote(nowMs, s) }
        }
    }

    /** A hold of about 1 s, fired while still held. Letting go afterwards is not a gesture. */
    fun hold(nowMs: Long, refusal: String? = null): List<RecorderEffect> {
        if (debounced(nowMs)) return emptyList()
        return when (val s = state) {
            RecorderState.Idle -> {
                if (refusal != null) return listOf(RecorderEffect.Refused(refusal))
                accept(nowMs) {
                    state = RecorderState.Note(nowMs, resumeRecording = false)
                    listOf(RecorderEffect.StartSession(RecordingKind.NOTE), RecorderEffect.PlayStartSound)
                }
            }
            is RecorderState.Recording -> {
                // Muted in the meantime: the recording stops and no note starts.
                if (refusal != null) return accept(nowMs) { stopRecording() } + RecorderEffect.Refused(refusal)
                accept(nowMs) {
                    state = RecorderState.Note(nowMs, resumeRecording = true)
                    listOf(
                        RecorderEffect.EndSession(RecordingKind.RECORDING),
                        RecorderEffect.StartSession(RecordingKind.NOTE),
                        RecorderEffect.PlayStartSound,
                    )
                }
            }
            is RecorderState.Note -> accept(nowMs) { stopNote(nowMs, s) }
        }
    }

    /** Called about once a second while something runs: enforces the maximum lengths. */
    fun tick(nowMs: Long): List<RecorderEffect> = when (val s = state) {
        RecorderState.Idle -> emptyList()
        is RecorderState.Recording ->
            if (nowMs - s.startedAtMs >= maxRecordingMs) stopRecording() else emptyList()
        is RecorderState.Note ->
            if (nowMs - s.startedAtMs >= maxNoteMs) stopNote(nowMs, s) else emptyList()
    }

    /**
     * Whatever runs stops and nothing resumes: the microphone was muted, recording was turned
     * off, or the app is shutting the recorder down. Not debounced.
     */
    fun stopAll(): List<RecorderEffect> = when (val s = state) {
        RecorderState.Idle -> emptyList()
        is RecorderState.Recording -> stopRecording()
        is RecorderState.Note -> {
            state = RecorderState.Idle
            listOf(RecorderEffect.EndSession(RecordingKind.NOTE), RecorderEffect.PlayStopSound)
        }
    }

    private fun startRecording(nowMs: Long, refusal: String?): List<RecorderEffect> {
        if (refusal != null) return listOf(RecorderEffect.Refused(refusal))
        return accept(nowMs) {
            state = RecorderState.Recording(nowMs)
            listOf(RecorderEffect.StartSession(RecordingKind.RECORDING), RecorderEffect.PlayStartSound)
        }
    }

    private fun stopRecording(): List<RecorderEffect> {
        state = RecorderState.Idle
        return listOf(RecorderEffect.EndSession(RecordingKind.RECORDING), RecorderEffect.PlayStopSound)
    }

    private fun stopNote(nowMs: Long, s: RecorderState.Note): List<RecorderEffect> {
        val effects = mutableListOf<RecorderEffect>(RecorderEffect.EndSession(RecordingKind.NOTE), RecorderEffect.PlayStopSound)
        state = if (s.resumeRecording) {
            effects += RecorderEffect.StartSession(RecordingKind.RECORDING, quiet = true)
            RecorderState.Recording(nowMs)
        } else {
            RecorderState.Idle
        }
        return effects
    }

    private fun debounced(nowMs: Long): Boolean {
        val last = lastGestureMs ?: return false
        return nowMs - last in 0 until debounceMs
    }

    private inline fun accept(nowMs: Long, block: () -> List<RecorderEffect>): List<RecorderEffect> {
        lastGestureMs = nowMs
        return block()
    }

    companion object {
        const val DEBOUNCE_MS = 1_000L
        const val MAX_NOTE_MS = 5 * 60_000L
        const val MAX_RECORDING_MS = 4 * 60 * 60_000L
        /** How long the button must be held for a voice note (E6's hardware button uses it too). */
        const val HOLD_MS = 1_000L
    }
}
