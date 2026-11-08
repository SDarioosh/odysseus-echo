package com.msp1974.vacompanion.odysseus.lock

// Odysseus fork: Lock mode in the satellite (docs/odysseus/lock-mode.md). One per Satellite, held by
// odysseus/OdysseusSatellite.kt; Satellite.kt's hooks are one line each.
//
// - watch() (Satellite.start): follows Lock mode for this session. It re-applies the wake word
//   mute (Satellite.muteMicrophone ORs in LockMode.isLocked) and on lock stops a turn and all sound.
// - serverPacket(): what Home Assistant starts on its own while locked is refused without leaving
//   it waiting (SatelliteLockRules).
// - action(): Home Assistant's custom actions: navigate odysseus://lock|unlock, and those ignored
//   while locked (SatelliteLockRules.IGNORED_ACTIONS).
// - refusesWake() (Satellite.handleWakeWordDetection): kept although the engine is muted while
//   locked and the manual "wake" action is ignored, because it is reachable: the mute is applied
//   by watch()'s collector after LockMode flips, so a detection already on its way when the device
//   locks would otherwise start a turn.

import com.msp1974.vacompanion.satellite.Satellite
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.WyomingEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber

class SatelliteLock(
    private val satellite: Satellite,
    /** The satellite has a pipeline that hasn't ended. */
    private val pipelineActive: () -> Boolean,
    /** Stops the satellite's pipeline and drops the audio kept since a wake word. */
    private val stopTurn: () -> Unit,
) {
    private var watchJob: Job? = null

    // Home Assistant's announcement audio being dropped because the device is locked.
    @Volatile
    private var droppingAudio = false

    /** Follows Lock mode for this session: mutes the wake word, stops a turn and all sound on lock. */
    fun watch() {
        watchJob?.cancel()
        watchJob = satellite.scope.launch {
            LockMode.locked.collect { locked ->
                satellite.muteMicrophone(satellite.config.isMuted)
                if (!locked) return@collect
                runCatching {
                    stopTurn()
                    satellite.mediaManager.stopAll()
                }.onFailure { Timber.e(it, "Lock mode: error stopping the satellite's audio") }
            }
        }
    }

    /**
     * While locked, what Home Assistant starts on its own is refused without leaving it waiting:
     * an announcement's audio is dropped and answered with `played` at its end, and a conversation
     * it starts (`transcribe`) gets an `audio-stop` at once, so no microphone audio is sent.
     * True if [type] was handled here.
     */
    fun serverPacket(type: String): Boolean {
        return when (SatelliteLockRules.serverPacket(type, LockMode.isLocked, droppingAudio, pipelineActive)) {
            SatelliteLockRules.Packet.PASS -> false
            SatelliteLockRules.Packet.START_DROPPING -> {
                Timber.i("Lock mode: announcement from Home Assistant dropped, locked")
                droppingAudio = true
                true
            }
            SatelliteLockRules.Packet.DROP -> true
            SatelliteLockRules.Packet.STOP_DROPPING -> {
                droppingAudio = false
                satellite.sendSatelliteMessage(satellite.clientId, WyomingEvent.PLAYED, buildJsonObject {})
                true
            }
            SatelliteLockRules.Packet.REFUSE -> {
                Timber.i("Lock mode: conversation started by Home Assistant refused, locked")
                satellite.sendSatelliteMessage(satellite.clientId, WyomingEvent.AUDIO_STOP, buildJsonObject { put("timestamp", Satellite.isoNow()) })
                true
            }
        }
    }

    /**
     * A custom action from Home Assistant. True if it was handled here (a lock or unlock through
     * navigate odysseus://lock|unlock) or must be ignored because the device is locked.
     */
    fun action(action: String, payload: String): Boolean {
        runCatching {
            if (action == "navigate" && payload.isNotEmpty()) {
                val url = Json.parseToJsonElement(payload).jsonObject["url"]?.jsonPrimitive?.content ?: ""
                if (LockMode.handleRemote(url)) return true
            }
        }.onFailure {
            // As before this moved out of Satellite.handleAction: a payload that doesn't parse ends the action.
            Timber.e("Failed to handle custom action $action: $it")
            return true
        }
        if (SatelliteLockRules.ignoresAction(LockMode.isLocked, action)) {
            Timber.i("Lock mode: action $action ignored, locked")
            return true
        }
        return false
    }

    /** No voice while locked. True if [detection] is ignored. */
    fun refusesWake(detection: WakeWordEngineProvider.WakeWordDetection): Boolean {
        if (!LockMode.isLocked) return false
        Timber.i("Lock mode: wake (${detection.wakeWordId}) ignored, locked")
        return true
    }
}
