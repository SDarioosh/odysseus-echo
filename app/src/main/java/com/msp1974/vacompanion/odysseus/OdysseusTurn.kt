package com.msp1974.vacompanion.odysseus

// Odysseus fork: what the device does for Odysseus during one voice turn (one audio pipeline).
// Satellite.startAudioPipeline creates one per pipeline (OdysseusTurn.begin), passes the
// pipeline's microphone flow through tee(), and reports every stage to onStage(). From there:
//
//   - VOICE_STOPPED (the owner stopped speaking): POST /satellite/turn in the background with the
//     device, area, wake word and, with "Check my voice" on, the command as WAV (≤ 15 s). It never
//     blocks or fails the pipeline.
//   - AWAITING_RESPONSE for ThinkingChime.DELAY_MS without TTS: the thinking sound, once.
//   - every stage: its time after the wake word (TurnTimings); at ENDED the times go to Odysseus
//     (POST /satellite/timing) in the background.
//
// Later per-turn features plug in here. Nothing happens when the device isn't linked to Odysseus.

import android.content.Context
import android.net.Uri
import com.msp1974.vacompanion.satellite.PipelineStage
import com.msp1974.vacompanion.satellite.PipelineStartMode
import com.msp1974.vacompanion.satellite.SatelliteMediaManager
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber

class OdysseusTurn private constructor(
    private val config: APPConfig,
    private val startedAtMs: Long,
    private val recorder: TurnAudioRecorder?,
    private val active: Boolean,
    startMode: PipelineStartMode,
) {
    private val timings = TurnTimings(startedAtMs, startMode.name.lowercase()).also {
        if (recorder != null) it.flag(TurnTimings.VOICE_CHECK)
    }
    @Volatile private var timingsPosted = false

    @Volatile private var stage = PipelineStage.INIT
    @Volatile private var posted = false
    @Volatile private var chimePlayed = false
    private var chimeJob: Job? = null

    /** The pipeline's microphone flow, also feeding the recorder when the voice check is on. */
    fun tee(frames: Flow<ShortArray>): Flow<ShortArray> = recorder?.tee(frames) ?: frames

    fun onStage(newStage: PipelineStage, mediaManager: SatelliteMediaManager) {
        stage = newStage
        if (!active) return
        timings.mark(newStage, System.currentTimeMillis())
        // HA may skip voice-stopped (straight to the transcript); post at the first stage past speech.
        if (!posted && newStage.ordinal >= PipelineStage.VOICE_STOPPED.ordinal && newStage != PipelineStage.ENDED &&
            newStage.ordinal <= PipelineStage.AWAITING_RESPONSE.ordinal
        ) {
            posted = true
            postContext()
        }
        when {
            newStage == PipelineStage.AWAITING_RESPONSE -> scheduleChime(mediaManager)
            newStage.ordinal > PipelineStage.AWAITING_RESPONSE.ordinal -> {
                chimeJob?.cancel(); chimeJob = null
            }
        }
        if (newStage == PipelineStage.ENDED) {
            recorder?.discard()
            postTimings()
        }
    }

    /** A wake word interrupted this turn (odysseus/BargeIn.kt). */
    fun markBargeIn() = timings.flag(TurnTimings.BARGE_IN)

    private fun postTimings() {
        if (timingsPosted) return
        timingsPosted = true
        if (current === this) current = null
        val body = timings.toJson(config.uuid)
        scope.launch {
            if (OdysseusLink.postTimings(body)) Timber.d("Odysseus turn timings sent: ${timings.marks()}")
        }
    }

    private fun postContext() {
        val wav = recorder?.finish()
        val form = SatelliteTurnForm(
            deviceId = config.uuid,
            deviceName = OdysseusLink.deviceName.ifBlank { "VACA ${config.uuid}" },
            area = config.area,
            wakeWord = config.wakeWord,
            startedAtMs = startedAtMs,
        )
        scope.launch {
            when (val r = OdysseusLink.postSatelliteTurn(form, wav)) {
                is OdysseusLink.PostResult.Ok -> Timber.i(
                    "Odysseus satellite turn: context ${r.response.contextId}, audio ${wav?.size ?: 0} bytes, " +
                        "speaker verified=${r.response.speaker?.verified} owner=${r.response.speaker?.isOwner}"
                )
                is OdysseusLink.PostResult.Failed -> Timber.w("Odysseus satellite turn: ${r.message}")
            }
        }
    }

    private fun scheduleChime(mediaManager: SatelliteMediaManager) {
        if (chimeJob != null || chimePlayed || !OdysseusLink.thinkingSound) return
        chimeJob = scope.launch {
            delay(ThinkingChime.DELAY_MS)
            val play = ThinkingChime.shouldPlay(
                enabled = OdysseusLink.thinkingSound,
                stage = stage,
                alreadyPlayed = chimePlayed,
                muted = config.isMuted,
                doNotDisturb = config.doNotDisturb,
                micMuted = VoiceActivity.micMuted.value,
            )
            if (play) {
                chimePlayed = true
                timings.flag(TurnTimings.THINKING_SOUND)
                Timber.d("Odysseus: answer is taking a while, playing the thinking sound")
                mediaManager.soundPlayer.play(Uri.parse(ThinkingChime.SOUND_URI))
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** The turn running now (the one a barge-in interrupts), until it ends. */
        @Volatile var current: OdysseusTurn? = null
            private set

        /** A wake word older than this isn't this turn's start (a pipeline started some other way). */
        private const val WAKE_RECENT_MS = 60_000L

        fun begin(context: Context, startMode: PipelineStartMode, config: APPConfig): OdysseusTurn {
            OdysseusLink.init(context)
            VoiceTurns.deviceId = config.uuid
            val now = System.currentTimeMillis()
            val listens = startMode != PipelineStartMode.START_STREAM_TTS
            if (listens && startMode != PipelineStartMode.WAKE_WORD_DETECTED) {
                // A follow-up HA asked for (continue conversation) or a server-started question:
                // a new turn for the card, but keep showing the question it answers.
                VoiceTurns.onFollowUpStarted()
            }
            val wakeAt = VoiceTurns.turnStartedAt
            val startedAt = if (startMode == PipelineStartMode.WAKE_WORD_DETECTED && wakeAt > 0 && now - wakeAt < WAKE_RECENT_MS) wakeAt else now
            val active = listens && OdysseusLink.isConfigured
            val recorder = if (active && OdysseusLink.checkVoice) TurnAudioRecorder(config.sampleRate) else null
            return OdysseusTurn(config, startedAt, recorder, active, startMode).also { if (active) current = it }
        }
    }
}
