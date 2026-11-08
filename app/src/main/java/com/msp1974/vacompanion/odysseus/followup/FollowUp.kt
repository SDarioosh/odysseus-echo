package com.msp1974.vacompanion.odysseus.followup

// Odysseus fork: answer "yes" without the wake word (docs/odysseus/follow-up.md).
//
// When a voice turn ends and Odysseus waits for a spoken "yes" from this device (the turn card's
// awaiting_confirmation, VoiceTurns.awaitingConfirmation), the device listens again by itself once
// its answer has finished playing: a soft listening sound (not the wake sound), then a pipeline
// started the manual-wake way (only this device, no arbitration report), for FollowUpSettings.seconds.
// Nothing heard → it ends quietly; there is no second try. Not when Home Assistant continues the
// conversation itself (it already listens again), and never while muted, in do-not-disturb,
// recording or with the screen blanked by the owner. The rules are in FollowUpRules (unit-tested).
//
// Satellite.startAudioPipeline's onFinish calls afterTurn() through OdysseusSatellite, whose [start]
// is startListening() with the satellite as its [Host].

import android.content.Context
import android.net.Uri
import com.msp1974.vacompanion.odysseus.HaRunGate
import com.msp1974.vacompanion.odysseus.VoiceActivity
import com.msp1974.vacompanion.odysseus.VoiceTurns
import com.msp1974.vacompanion.odysseus.record.RecorderController
import com.msp1974.vacompanion.odysseus.record.RecorderState
import com.msp1974.vacompanion.satellite.PipelineEndReason
import com.msp1974.vacompanion.satellite.PipelineStartMode
import com.msp1974.vacompanion.satellite.SatelliteMediaManager
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

object FollowUp {
    const val SOUND_URI = "asset:///odysseus/listening.wav"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * A pipeline ended ([startMode], [reason]; [haContinues]: Home Assistant asked to continue the
     * conversation, so the satellite listens again by itself already). Decides in the background
     * whether to listen for a "yes"; [start] starts the listening pipeline with the given limit in
     * ms and returns false when the satellite is busy. [busy]: a pipeline runs or a wake word is
     * being handled.
     */
    @Synchronized
    fun afterTurn(
        context: Context,
        config: APPConfig,
        mediaManager: SatelliteMediaManager,
        startMode: PipelineStartMode,
        reason: PipelineEndReason,
        haContinues: Boolean,
        busy: () -> Boolean,
        start: suspend (listenLimitMs: Long) -> Boolean,
    ) {
        FollowUpSettings.init(context)
        if (!FollowUpRules.offersAfter(FollowUpSettings.enabled, startMode, reason, haContinues)) return
        val turn = VoiceTurns.turnStartedAt
        job?.cancel()
        job = scope.launch {
            // The answer has normally finished playing by now; never listen over it.
            withTimeoutOrNull(FollowUpRules.PLAYBACK_WAIT_MAX_MS) {
                while (mediaManager.voicePlayer.isRunning()) delay(50)
            }
            fun conditions(awaiting: Boolean?) = FollowUpRules.Conditions(
                enabled = FollowUpSettings.enabled,
                sameTurn = VoiceTurns.turnStartedAt == turn,
                muted = config.isMuted || com.msp1974.vacompanion.odysseus.lock.LockMode.isLocked, // Lock mode (odysseus/lock/)
                micMuted = VoiceActivity.micMuted.value,
                doNotDisturb = config.doNotDisturb,
                recording = RecorderController.state.value.recorder != RecorderState.Idle,
                screenBlanked = config.screenSaver,
                busy = busy(),
                awaitingConfirmation = awaiting,
            )
            // The cheap checks first, so the hub is asked only when they pass.
            FollowUpRules.skipReason(conditions(null))?.let {
                Timber.d("Follow-up: not listening ($it)"); return@launch
            }
            val awaiting = withTimeoutOrNull(FollowUpRules.AWAITING_CHECK_MAX_MS) {
                VoiceTurns.awaitingConfirmation()
            } ?: false
            // Again, with the answer: anything may have changed while the hub was asked.
            FollowUpRules.skipReason(conditions(awaiting))?.let {
                Timber.d("Follow-up: not listening ($it)"); return@launch
            }
            val seconds = FollowUpSettings.seconds
            Timber.i("Follow-up: Odysseus waits for a yes; listening up to $seconds s without the wake word")
            if (!start(FollowUpRules.listenLimitMs(seconds))) Timber.i("Follow-up: satellite busy, not listening")
        }
    }

    /** The soft listening sound; returns once it has (nearly) finished, so it isn't streamed to HA. */
    suspend fun playSound(mediaManager: SatelliteMediaManager) {
        runCatching { mediaManager.soundPlayer.play(Uri.parse(SOUND_URI)) }
            .onFailure { Timber.w("Follow-up: listening sound failed: ${it.message}") }
        delay(FollowUpRules.CHIME_MS)
    }

    /** What startListening needs of the satellite; [B] is the audio kept from the end of the sound. */
    interface Host<B> {
        /** The satellite still runs (HA may have reconnected and replaced it while the answer played). */
        val running: Boolean
        val connected: Boolean
        fun responding(): Boolean
        /** The satellite is handling a wake word; set while the follow-up starts, so none interferes. */
        var arbitrating: Boolean
        /** The listening sound ([playSound]). */
        suspend fun playSound()
        /** Ducks the other devices as a manual wake does: no arbitration report. */
        suspend fun duckOthers()
        /** Starts keeping the microphone's audio from now (the satellite's detection buffer). */
        fun keepAudio(): B
        fun dropAudio()
        fun startPipeline(buffer: B, listenLimitMs: Long)
    }

    /**
     * The follow-up's pipeline, started the manual-wake way: only this device, no arbitration
     * report (it ducks the others as a manual wake does); the listening sound instead of the wake
     * sound; audio kept from the end of that sound. False if the satellite was busy.
     */
    suspend fun <B> startListening(host: Host<B>, listenLimitMs: Long): Boolean {
        if (!FollowUpRules.canStartListening(host.running, host.connected, host.responding(), host.arbitrating)) return false
        host.arbitrating = true
        try {
            host.playSound()
            if (!host.running || host.responding()) return false
            host.duckOthers()
            val buffer = host.keepAudio()
            if (!HaRunGate.awaitIdle()) { host.dropAudio(); return false }
            host.startPipeline(buffer, listenLimitMs)
            return true
        } finally {
            host.arbitrating = false
        }
    }
}
