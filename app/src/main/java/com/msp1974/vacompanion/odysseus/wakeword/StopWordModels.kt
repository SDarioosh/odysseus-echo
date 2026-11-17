package com.msp1974.vacompanion.odysseus.wakeword

import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWord
import com.msp1974.vacompanion.wakeword.models.DetectionInfo
import timber.log.Timber

/**
 * Odysseus fork (docs/odysseus/power.md): the stop word models of one MicroWakeWordDetector, each
 * behind a [StopWordGate], so they run only while [wanted] (a "stop" could stop something,
 * [StopWordRules]). The wake word models pass straight through, untouched; so does the shared
 * frontend, which the detector keeps running for them. Lives and is called on the detector's
 * thread only; [wanted] is read there, once per 10 ms feature frame.
 */
class StopWordModels(stopModels: List<MicroWakeWord>, private val wanted: () -> Boolean) {
    private val gates: Map<MicroWakeWord, StopWordGate<DetectionInfo>> = stopModels.associateWith { model ->
        StopWordGate(model.featureFramesPerInference, model.probabilityWindow) { model.processAudioFeatures(it) }
    }

    /**
     * [model]'s result for one feature frame, as MicroWakeWord.processAudioFeatures gives it, or
     * null while it is a paused stop model (no result to report).
     */
    fun process(model: MicroWakeWord, features: FloatArray): DetectionInfo? {
        val gate = gates[model] ?: return model.processAudioFeatures(features)
        val wasRunning = gate.running
        val result = gate.onFrame(features, wanted())
        if (gate.running != wasRunning) {
            if (gate.running) Timber.d("Stop word model ${model.id} on, warmed up with ${gate.lastWarmUpFrames} frames")
            else Timber.d("Stop word model ${model.id} paused: nothing to stop")
        }
        return result
    }
}
