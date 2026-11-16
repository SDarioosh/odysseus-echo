package com.msp1974.vacompanion.odysseus.arbitration

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Odysseus fork: how loud the wake word was at this device, for arbitration and the calibration
 * wizard (docs/wake-word-arbitration.md#level-and-calibration). Fed the raw microphone frames by
 * audio/MicLevelMonitor (one hook), so it hears the signal before any processing.
 *
 * What it measures, and why it differs from upstream's "loudest 80 ms frame in the last 2 s":
 *  - **Speech band.** A 300 Hz high-pass (2nd-order Butterworth) runs before the level is taken.
 *    Rumble, hum, a microphone's DC offset and music's bass depend on where a device stands, not on
 *    how near the speaker is; speech's energy is above it. Higher frequencies are also the most
 *    directional part of a voice, so the device being spoken towards reads relatively louder.
 *  - **The wake word itself.** Only the last [WAKE_WORD_WINDOW_MS] (the phrase plus the engine's
 *    delay in reporting it) rather than 2 s, so less of what came before the wake word counts.
 *  - **Not one frame.** The power average of the loudest [WAKE_WORD_TOP_MS] of that window (three
 *    80 ms frames), so a single click, tap or plosive can't decide on its own.
 *  - **A noise floor** (the 20th percentile of the frames in the last 5 s) is reported beside it. It
 *    is only logged, so the owner can see from real decisions whether background noise or a device's
 *    own playback skewed a level; it decides nothing.
 *
 * Levels are dBFS (0 is full scale), floored at [FLOOR_DBFS]. [update] is called from the
 * microphone's read thread and the rest from elsewhere.
 */
class WakeLevelMeter {

    private class Frame(val durationMs: Float, val levelDb: Float)

    private val frames = ArrayDeque<Frame>()
    private var framesMs = 0f

    // The high-pass filter: coefficients for [filterRate], state carried across frames.
    private var filterRate = 0
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    @Synchronized
    fun update(frame: ShortArray, sampleRate: Int) {
        if (frame.isEmpty() || sampleRate <= 0) return
        if (sampleRate != filterRate) setUpFilter(sampleRate)
        var sum = 0.0
        for (s in frame) {
            val x = s / 32768.0
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            sum += y * y
        }
        val levelDb = (10 * log10(sum / frame.size + 1e-12)).toFloat().coerceAtLeast(FLOOR_DBFS)
        val durationMs = frame.size * 1000f / sampleRate
        frames.addLast(Frame(durationMs, levelDb))
        framesMs += durationMs
        while (framesMs > HISTORY_MS && frames.size > 1) framesMs -= frames.removeFirst().durationMs
    }

    /**
     * The wake word's level: the power average of the loudest [topMs] of audio over the last
     * [windowMs]. Null if nothing has been heard yet.
     */
    @Synchronized
    fun wakeWordDbfs(windowMs: Long = WAKE_WORD_WINDOW_MS, topMs: Long = WAKE_WORD_TOP_MS): Float? {
        if (frames.isEmpty()) return null
        val recent = ArrayList<Frame>()
        var covered = 0f
        for (i in frames.indices.reversed()) {
            recent.add(frames[i])
            covered += frames[i].durationMs
            if (covered >= windowMs) break
        }
        recent.sortByDescending { it.levelDb }
        var energyMs = 0.0
        var taken = 0f
        for (f in recent) {
            energyMs += 10.0.pow(f.levelDb / 10.0) * f.durationMs
            taken += f.durationMs
            if (taken >= topMs) break
        }
        return (10 * log10(energyMs / taken + 1e-12)).toFloat().coerceAtLeast(FLOOR_DBFS)
    }

    /** The background level: the 20th percentile of the frames in the last few seconds. Null if none yet. */
    @Synchronized
    fun noiseFloorDbfs(): Float? {
        if (frames.isEmpty()) return null
        val sorted = frames.map { it.levelDb }.sorted()
        return sorted[((sorted.size - 1) * NOISE_PERCENTILE).toInt()]
    }

    @Synchronized
    fun reset() {
        frames.clear()
        framesMs = 0f
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    // RBJ audio EQ cookbook high-pass, Q = 1/sqrt(2) (Butterworth).
    private fun setUpFilter(sampleRate: Int) {
        val w0 = 2 * PI * HIGH_PASS_HZ / sampleRate
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val cosW0 = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + cosW0) / 2 / a0
        b1 = -(1 + cosW0) / a0
        b2 = (1 + cosW0) / 2 / a0
        a1 = -2 * cosW0 / a0
        a2 = (1 - alpha) / a0
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        filterRate = sampleRate
    }

    companion object {
        /** "Hey Athena" (about 0.9 s) plus the time the engine takes to report it. */
        const val WAKE_WORD_WINDOW_MS = 1_500L

        /** How much of the loudest audio in that window is averaged: three 80 ms frames. */
        const val WAKE_WORD_TOP_MS = 240L

        /** Above mains hum, rumble and bass; below most of a voice's energy. */
        const val HIGH_PASS_HZ = 300.0

        const val FLOOR_DBFS = -100f

        private const val HISTORY_MS = 5_000f
        private const val NOISE_PERCENTILE = 0.2
    }
}
