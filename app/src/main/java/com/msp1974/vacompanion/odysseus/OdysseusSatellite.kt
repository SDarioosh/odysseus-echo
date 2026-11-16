package com.msp1974.vacompanion.odysseus

// Odysseus fork: the one object satellite/Satellite.kt knows of the fork. Each Satellite holds one
// (`private val odysseus = OdysseusSatellite(...)`, declared with the fork's other lines at the end
// of the class) and its hooks are one-line calls into it, so the upstream file needs no fork imports.
// What it needs of Satellite's private state it is handed as functions at construction.
//
// - Run gate (HaRunGate.kt): reset when a satellite is made (a new connection) and whenever its
//   clientId is set (a takeover by another Home Assistant connection, or a disconnect; the
//   clientId setter calls onClientId). This used to be in wyoming/WyomingTCPServer.kt.
// - Barge-in (BargeIn.kt): onWakeWord(), at the top of handleWakeWordDetection.
// - Lock mode (lock/SatelliteLock.kt): onStart(), onServerPacket(), onWakeWord(), [locked].
// - The turn (OdysseusTurn.kt, ListeningIndicator.kt's VoiceActivity): beginTurn(), onStage().
// - The listening indicator for a wake word that ends without a turn (WakeIndicatorPolicy.kt):
//   showListening(), readyForTurn(), wakeEnded().
// - Follow-up (followup/FollowUp.kt): afterTurn().
// - Stop word model only while something can be stopped (wakeword/StopWordGate.kt): stopWordUseful().
// - Wi-Fi (power/WifiPerformance.kt): full performance from the wake word, or a pipeline starting,
//   while the satellite arbitrates or has a pipeline (holdVoice()).

import com.msp1974.vacompanion.audio.DetectionBuffer
import com.msp1974.vacompanion.audio.MicLevelMonitor
import com.msp1974.vacompanion.odysseus.arbitration.WakeLevelReading
import com.msp1974.vacompanion.broadcasts.VacaUdpBroadcast
import com.msp1974.vacompanion.odysseus.followup.FollowUp
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.odysseus.lock.SatelliteLock
import com.msp1974.vacompanion.odysseus.wakeword.StopWordRules
import com.msp1974.vacompanion.odysseus.power.WifiNeed
import com.msp1974.vacompanion.odysseus.power.WifiPerformance
import com.msp1974.vacompanion.satellite.PipelineEndReason
import com.msp1974.vacompanion.satellite.PipelineStage
import com.msp1974.vacompanion.satellite.PipelineStartMode
import com.msp1974.vacompanion.satellite.Satellite
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.SatelliteState
import com.msp1974.vacompanion.wyoming.WyomingCustomEventType
import com.msp1974.vacompanion.wyoming.WyomingEvent
import com.msp1974.vacompanion.wyoming.WyomingPacket
import kotlin.reflect.KMutableProperty0
import timber.log.Timber

class OdysseusSatellite(
    private val satellite: Satellite,
    /** Satellite.arbitratingWakeWord. */
    private val arbitrating: KMutableProperty0<Boolean>,
    /** Satellite has a pipeline that hasn't ended. */
    private val pipelineActive: () -> Boolean,
    /** Satellite's pipeline is streaming its response (what Satellite.handleStopWord stops). */
    private val ttsStreaming: () -> Boolean,
    /** Starts Satellite's detection buffer: the microphone's audio from now on. */
    private val keepAudio: () -> DetectionBuffer,
    /** Satellite.cancelDetectionBuffer. */
    private val dropAudio: () -> Unit,
) {
    init {
        HaRunGate.reset() // a new satellite: Home Assistant has no run for it
    }

    private val lock = SatelliteLock(satellite, pipelineActive) {
        satellite.stopAudioPipeline()
        dropAudio()
    }

    /** Lock mode is locked: the wake word engine stays muted. */
    val locked: Boolean get() = LockMode.isLocked

    /** Satellite.start. */
    fun onStart() = lock.watch()

    /** Satellite.clientId was set: another connection took over, or it disconnected. */
    fun onClientId() = HaRunGate.reset()

    /**
     * Every packet from Home Assistant, before Satellite.processMessage routes it. True when it
     * was handled here and must go no further. In this order: the run gate drops what HA still
     * sends for an interrupted run; Lock mode refuses what HA starts on its own while locked; for
     * custom actions, Lock mode's own navigate URLs and the actions ignored while locked.
     */
    fun onServerPacket(packet: WyomingPacket): Boolean {
        if (packet.type == WyomingEvent.CUSTOM_EVENT) {
            if (packet.getProp("event_type") != WyomingCustomEventType.ACTION) return false
            return lock.action(packet.getProp("action"), packet.getProp("payload"))
        }
        return HaRunGate.onServerPacket(packet.type) || lock.serverPacket(packet.type)
    }

    /**
     * The start of Satellite.handleWakeWordDetection (a spoken or manual wake). True: ignore it
     * (locked). Otherwise, unless the satellite is responding (a manual wake is then ignored),
     * a run HA still has for this device is interrupted (barge-in, BargeIn.kt): after a wake word
     * that stopped this device's turn (onWakeWordDetected → interruptResponse), and after a turn
     * stuck in "Thinking…" that isResponding() no longer counts. Done before the wake sound and
     * arbitration so nothing of the interrupted run can start playing meanwhile; it is a no-op
     * when HA has no run, and once per run.
     */
    fun onWakeWord(detection: WakeWordEngineProvider.WakeWordDetection): Boolean {
        if (lock.refusesWake(detection)) return true
        holdVoice()
        if (!satellite.isResponding()) BargeIn.interruptHaRun(satellite.config.uuid)
        return false
    }

    /**
     * A "stop" could stop something now (StopWordRules, the same checks as Satellite.handleStopWord).
     * Read by the wake word engine's thread on every 10 ms feature frame: cheap reads only.
     */
    fun stopWordUseful(): Boolean =
        StopWordRules.useful(ttsStreaming(), satellite.mediaManager.alarmPlayer.isSounding())


    /**
     * Wi-Fi out of power save for this voice interaction: taken before this returns, so before the
     * wake word is reported to the master, and its decision isn't held at the access point; kept
     * while arbitrating or a pipeline runs, then a short grace (docs/wake-word-arbitration.md).
     */
    private fun holdVoice() = WifiPerformance.holdWhile(WifiNeed.VOICE) { arbitrating.get() || pipelineActive() }

    // "Listening…" from the wake word until a turn starts or the wake word ends without one
    // (WakeIndicatorPolicy.kt): the one place that clears it when this device doesn't answer.
    private val wakeIndicator = WakeIndicatorPolicy(show = VoiceActivity::onWakeWord, hide = VoiceActivity::onIdle)

    /** The wake word's calibrated level and noise floor, for arbitration (arbitration/WakeLevelMeter.kt). */
    fun wakeLevel(monitor: MicLevelMonitor) = WakeLevelReading.of(monitor.wake, satellite.config.wakeLevelOffsetDb)

    /** Show "Listening…" at once: a wake word was heard and this device may answer it. */
    fun showListening() = wakeIndicator.wakeHeard()

    /**
     * The end of Satellite.handleWakeWordDetection, however it ended (its `finally`). Unless a turn
     * started, the indicator goes back to idle now: lost arbitration, a late report told it lost,
     * Lock mode, HA's previous run never ending, dropped audio, an error.
     */
    fun wakeEnded() = wakeIndicator.wakeEnded()

    fun showIdle() = VoiceActivity.onIdle()

    /**
     * Whether the wake word this device won may start a turn now. Never while HA's previous run is
     * still going (HaRunGate.kt); not if the device locked meanwhile (the engine's mute comes too
     * late for a wake word already being arbitrated) or the audio kept since the wake word was
     * dropped (Lock mode drops it; a pipeline started with it would stream nothing). False: give up;
     * wakeEnded() clears the indicator.
     */
    suspend fun readyForTurn(buffer: DetectionBuffer): Boolean {
        if (!HaRunGate.awaitIdle()) return false
        if (LockMode.isLocked) {
            Timber.i("Lock mode: locked while the wake word was arbitrated, not answering")
            return false
        }
        if (buffer.isCancelled) {
            Timber.w("The audio kept since the wake word was dropped, not answering")
            return false
        }
        return true
    }

    /** Satellite.startAudioPipeline, once the old pipeline is stopped. */
    fun beginTurn(startStage: PipelineStartMode): OdysseusTurn {
        // Not for an announcement (HA's audio-start): that is no Assist run.
        if (startStage != PipelineStartMode.START_STREAM_TTS) HaRunGate.runStarted()
        holdVoice() // also announcements, follow-ups and continued conversations
        wakeIndicator.turnStarted() // its stages drive the indicator from now on
        return OdysseusTurn.begin(satellite.context, startStage, satellite.config)
    }

    /** The pipeline's onStateChange: the on-screen indicator and the turn. */
    fun onStage(turn: OdysseusTurn, state: PipelineStage) {
        // An older turn's late stages (its ENDED after a barge-in stopped it) don't hide the new
        // wake word's "Listening…".
        if (wakeIndicator.stageShown()) VoiceActivity.onPipelineStage(state)
        turn.onStage(state, satellite.mediaManager)
    }

    /** The pipeline's onFinish: listen for a "yes" without the wake word when Odysseus waits on one. */
    fun afterTurn(startStage: PipelineStartMode, reason: PipelineEndReason, continueConversation: Boolean) {
        FollowUp.afterTurn(
            satellite.context, satellite.config, satellite.mediaManager, startStage, reason, continueConversation,
            busy = { satellite.isResponding() || arbitrating.get() },
        ) { FollowUp.startListening(followUpHost, it) }
    }

    private val followUpHost = object : FollowUp.Host<DetectionBuffer> {
        override val running get() = satellite.state == SatelliteState.RUNNING
        override val connected get() = satellite.clientId.isNotEmpty()
        override fun responding() = satellite.isResponding()
        override var arbitrating: Boolean
            get() = this@OdysseusSatellite.arbitrating.get()
            set(value) = this@OdysseusSatellite.arbitrating.set(value)
        override suspend fun playSound() = FollowUp.playSound(satellite.mediaManager)
        override suspend fun duckOthers() {
            VacaUdpBroadcast.instance?.arbitrateWakeWord(1.0f, contest = false)
        }
        override fun keepAudio() = this@OdysseusSatellite.keepAudio()
        override fun dropAudio() = this@OdysseusSatellite.dropAudio()
        override fun startPipeline(buffer: DetectionBuffer, listenLimitMs: Long) =
            satellite.startAudioPipeline(PipelineStartMode.CONTINUE_CONVERSATION, buffer = buffer, followUpListenMs = listenLimitMs)
    }
}
