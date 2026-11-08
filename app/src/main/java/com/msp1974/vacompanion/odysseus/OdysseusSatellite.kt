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
// - Follow-up (followup/FollowUp.kt): afterTurn().

import com.msp1974.vacompanion.audio.DetectionBuffer
import com.msp1974.vacompanion.broadcasts.VacaUdpBroadcast
import com.msp1974.vacompanion.odysseus.followup.FollowUp
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.odysseus.lock.SatelliteLock
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

class OdysseusSatellite(
    private val satellite: Satellite,
    /** Satellite.arbitratingWakeWord. */
    private val arbitrating: KMutableProperty0<Boolean>,
    /** Satellite has a pipeline that hasn't ended. */
    pipelineActive: () -> Boolean,
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
        if (!satellite.isResponding()) BargeIn.interruptHaRun(satellite.config.uuid)
        return false
    }

    /** Show "Listening…" at once. */
    fun showListening() = VoiceActivity.onWakeWord()

    fun showIdle() = VoiceActivity.onIdle()

    /** Never start a run while HA's previous one is still going (HaRunGate.kt). False: give up. */
    suspend fun awaitHaIdle(): Boolean = HaRunGate.awaitIdle().also { if (!it) VoiceActivity.onIdle() }

    /** Satellite.startAudioPipeline, once the old pipeline is stopped. */
    fun beginTurn(startStage: PipelineStartMode): OdysseusTurn {
        // Not for an announcement (HA's audio-start): that is no Assist run.
        if (startStage != PipelineStartMode.START_STREAM_TTS) HaRunGate.runStarted()
        return OdysseusTurn.begin(satellite.context, startStage, satellite.config)
    }

    /** The pipeline's onStateChange: the on-screen indicator and the turn. */
    fun onStage(turn: OdysseusTurn, state: PipelineStage) {
        VoiceActivity.onPipelineStage(state)
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
