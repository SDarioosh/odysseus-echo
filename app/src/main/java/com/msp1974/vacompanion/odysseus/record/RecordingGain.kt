package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: automatic gain for recordings (docs/odysseus/recording.md, "Level"). The raw
// microphone is very quiet on some devices: on the Echo Show 5 speech peaked around 170 of 32767
// (about -46 dBFS), too quiet for Athena's speech detection, which then found no speech and made
// no conversation. This raises quiet audio towards TARGET_PEAK and never turns anything down
// (gain >= 1), so a loud microphone is left as it is. Plain Kotlin, unit-tested
// (RecordingGainTest). One per recording: RecordingCapture feeds it every frame, in order.
//
// How: an envelope follows the loudest sample: it jumps up at once to a louder frame and falls
// slowly (halving over RELEASE_HALF_MS), so the gain drops before a loud frame and rises slowly
// after it, without pumping on every syllable. Gain = TARGET_PEAK / envelope, between 1 and
// MAX_GAIN. A rising gain is ramped across the frame; a falling one applies from the frame's first
// sample, so nothing clips. Samples are clamped to PCM16 all the same.

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class RecordingGain(
    private val targetPeak: Double = TARGET_PEAK,
    private val maxGain: Double = MAX_GAIN,
    frameMs: Long = FRAME_MS,
    releaseHalfMs: Long = RELEASE_HALF_MS,
) {
    private val decay = 0.5.pow(frameMs.toDouble() / releaseHalfMs)

    /** The loudest recent sample, decaying. Nothing heard yet. */
    private var envelope = 0.0

    /** The gain the last frame ended on. Starts at the most: a loud first frame lowers it at once. */
    var gain = maxGain
        private set

    /** Applies the gain to [frame] in place and returns it. */
    fun process(frame: ShortArray): ShortArray {
        if (frame.isEmpty()) return frame
        var peak = 0
        for (s in frame) {
            val a = abs(s.toInt())
            if (a > peak) peak = a
        }
        envelope = maxOf(peak.toDouble(), envelope * decay, 1.0)
        val target = (targetPeak / envelope).coerceIn(1.0, maxGain)
        val start = if (target < gain) target else gain
        val step = (target - start) / frame.size
        var g = start
        for (i in frame.indices) {
            g += step
            frame[i] = (frame[i] * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        gain = target
        return frame
    }

    companion object {
        /** Loud speech is brought up to about -6 dBFS. */
        const val TARGET_PEAK = 16_000.0

        /** At most +40 dB: enough for the Echo Show 5's raw microphone, not enough to make hiss into "speech". */
        const val MAX_GAIN = 100.0

        /** The shared microphone's frames: 1280 samples at 16 kHz. */
        const val FRAME_MS = 80L

        /** After a loud sound the gain takes about this long to double again. */
        const val RELEASE_HALF_MS = 2_000L
    }
}
