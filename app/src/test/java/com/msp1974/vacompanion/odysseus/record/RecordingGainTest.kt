package com.msp1974.vacompanion.odysseus.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class RecordingGainTest {

    /** 80 ms of a 200 Hz tone peaking at [peak]. */
    private fun tone(peak: Int, n: Int = 1280) =
        ShortArray(n) { (peak * sin(2 * Math.PI * 200 * it / 16_000.0)).toInt().toShort() }

    private fun ShortArray.peak() = maxOf(abs(min().toInt()), abs(max().toInt()))

    @Test
    fun quietSpeechLikeTheEchoShowIsRaisedNearTheTarget() {
        // Measured on the Echo Show 5: speech peaks around 170 of 32767.
        val g = RecordingGain()
        var out = ShortArray(0)
        repeat(20) { out = g.process(tone(170)) }
        assertTrue("peak ${out.peak()}", out.peak() in 14_000..17_000)
    }

    @Test
    fun theFirstFrameIsRaisedAtOnce() {
        val out = RecordingGain().process(tone(170))
        assertTrue("peak ${out.peak()}", out.peak() > 14_000)
    }

    @Test
    fun aLoudMicrophoneIsNeverTurnedDown() {
        val g = RecordingGain()
        repeat(10) {
            val frame = tone(25_000)
            val before = frame.copyOf()
            g.process(frame)
            assertEquals(before.toList(), frame.toList())
        }
        assertEquals(1.0, g.gain, 0.0)
    }

    @Test
    fun aSuddenLoudFrameAfterQuietOnesDoesNotClip() {
        val g = RecordingGain()
        repeat(50) { g.process(tone(100)) }
        val out = g.process(tone(20_000))
        assertTrue("peak ${out.peak()}", out.peak() <= 20_001)   // gain dropped to 1 before the first sample
    }

    @Test
    fun theGainRisesSlowlyAfterALoudSound() {
        val g = RecordingGain()
        g.process(tone(16_000))
        assertEquals(1.0, g.gain, 1e-9)
        repeat(25) { g.process(tone(100)) }   // 2 s: the envelope halves once
        assertEquals(2.0, g.gain, 0.01)
    }

    @Test
    fun silenceIsRaisedNoMoreThanTheMaximum() {
        val g = RecordingGain()
        repeat(100) { g.process(tone(10)) }
        assertEquals(RecordingGain.MAX_GAIN, g.gain, 0.0)
        val zeros = g.process(ShortArray(1280))
        assertTrue(zeros.all { it == 0.toShort() })
    }

    @Test
    fun anEmptyFrameIsLeftAlone() {
        assertEquals(0, RecordingGain().process(ShortArray(0)).size)
    }
}
