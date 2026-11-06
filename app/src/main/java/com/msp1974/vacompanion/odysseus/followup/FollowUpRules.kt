package com.msp1974.vacompanion.odysseus.followup

// Odysseus fork: when the device listens again by itself for the answer to a question Odysseus
// asked ("Add Dentist on Tuesday at 3 PM to your calendar?"), so "yes" needs no wake word
// (docs/odysseus/follow-up.md). Pure Kotlin, unit-tested (FollowUpRulesTest); FollowUp.kt runs it.

import com.msp1974.vacompanion.satellite.PipelineEndReason
import com.msp1974.vacompanion.satellite.PipelineStartMode

object FollowUpRules {
    const val DEFAULT_SECONDS = 8
    const val MIN_SECONDS = 3
    const val MAX_SECONDS = 15

    /** Choices offered in the settings menu. */
    val SECONDS_CHOICES = listOf(3, 5, 8, 10, 15)

    /** The listening sound's length (assets/odysseus/listening.wav, 0.35 s) plus a little. */
    const val CHIME_MS = 400L

    /** The answer normally finished playing when the pipeline ends; never wait longer than this. */
    const val PLAYBACK_WAIT_MAX_MS = 30_000L

    /** Asking the hub whether the turn waits for a "yes" (two tries, 1.5 s apart) gives up after this. */
    const val AWAITING_CHECK_MAX_MS = 5_000L

    /** HA's error when speech-to-text heard nothing: a follow-up then ends quietly. */
    const val NOTHING_HEARD_CODE = "stt-no-text-recognized"

    fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** How long the follow-up pipeline may sit in LISTENING without hearing speech. */
    fun listenLimitMs(seconds: Int): Long = clampSeconds(seconds) * 1000L

    /**
     * Whether a turn that just ended may be followed by listening without the wake word, before
     * asking the hub anything. Only after a turn started by a wake word (spoken or manual) that
     * ended normally. Never after a follow-up (ours or Home Assistant's continue conversation, both
     * CONTINUE_CONVERSATION), so there is one try only; never after a server-started question or
     * an announcement; never after a barge-in, a time-out or an error. And never when Home
     * Assistant continues the conversation itself ([haContinues]): it already listens again.
     */
    fun offersAfter(
        enabled: Boolean,
        startMode: PipelineStartMode,
        reason: PipelineEndReason,
        haContinues: Boolean,
    ): Boolean = enabled &&
        startMode == PipelineStartMode.WAKE_WORD_DETECTED &&
        reason == PipelineEndReason.END_OF_PIPELINE &&
        !haContinues

    /** Why the device doesn't listen again; checked in this order. */
    enum class Skip {
        DISABLED,
        NEW_TURN,
        MUTED,
        MIC_MUTED,
        DO_NOT_DISTURB,
        RECORDING,
        SCREEN_BLANKED,
        BUSY,
        NOT_AWAITING,
    }

    /** What is true once the answer finished playing. */
    data class Conditions(
        val enabled: Boolean,
        /** No other turn started since the one that ended (a wake word, a server question). */
        val sameTurn: Boolean,
        /** Home Assistant's mute switch for the satellite. */
        val muted: Boolean,
        /** The privacy button / Android's microphone mute. */
        val micMuted: Boolean,
        val doNotDisturb: Boolean,
        /** A recording or a voice note is running (odysseus/record/). */
        val recording: Boolean,
        /** The owner blanked the screen (the screen saver / black screen). */
        val screenBlanked: Boolean,
        /** A pipeline is running or a wake word is being handled. */
        val busy: Boolean,
        /**
         * Odysseus waits for a spoken "yes" from this device on that turn. Null: not asked yet
         * (the cheap checks run first, so the hub is asked only when they pass).
         */
        val awaitingConfirmation: Boolean?,
    )

    /** Null: listen now. Otherwise why not. */
    fun skipReason(c: Conditions): Skip? = when {
        !c.enabled -> Skip.DISABLED
        !c.sameTurn -> Skip.NEW_TURN
        c.muted -> Skip.MUTED
        c.micMuted -> Skip.MIC_MUTED
        c.doNotDisturb -> Skip.DO_NOT_DISTURB
        c.recording -> Skip.RECORDING
        c.screenBlanked -> Skip.SCREEN_BLANKED
        c.busy -> Skip.BUSY
        c.awaitingConfirmation == false -> Skip.NOT_AWAITING
        else -> null
    }

    /**
     * Whether the satellite may start the follow-up's pipeline now: still [running] (Home
     * Assistant may have reconnected, and a new satellite replaced this one, while the answer
     * played), [connected], not [responding] and not handling a wake word ([arbitrating]).
     */
    fun canStartListening(running: Boolean, connected: Boolean, responding: Boolean, arbitrating: Boolean): Boolean =
        running && connected && !responding && !arbitrating

    /** A follow-up pipeline that heard nothing ends quietly: no error sound, no toast. */
    fun isNothingHeard(errorCode: String): Boolean = errorCode == NOTHING_HEARD_CODE

    /** The listening limit has passed: [inStageMs] in LISTENING, [limitMs] from [listenLimitMs]. */
    fun listeningExpired(inStageMs: Long, limitMs: Long): Boolean = inStageMs > limitMs
}
