package com.msp1974.vacompanion.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** The unboxed conversions (docs/odysseus/power.md) give exactly what the old map-based ones did. */
class AudioDSPTest {
    private val dsp = AudioDSP()
    private val random = Random(3)
    private fun shorts(n: Int) = ShortArray(n) { random.nextInt(-32768, 32768).toShort() }

    @Test
    fun shortArrayTo16BitPCMFloatMatchesTheOldConversion() {
        for (n in listOf(0, 1, 160, 1280)) {
            val frame = shorts(n)
            assertArrayEquals(frame.map { it.toFloat() }.toFloatArray(), dsp.shortArrayTo16BitPCMFloat(frame), 0f)
        }
        val edges = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        assertArrayEquals(edges.map { it.toFloat() }.toFloatArray(), dsp.shortArrayTo16BitPCMFloat(edges), 0f)
    }

    @Test
    fun normaliseAudioBufferMatchesTheOldConversion() {
        for (n in listOf(0, 1, 1280)) {
            val frame = shorts(n)
            assertArrayEquals(frame.map { it.toFloat() / 32768.0f }.toFloatArray(), dsp.normaliseAudioBuffer(frame), 0f)
        }
    }

    @Test
    fun audioLevelMatchesTheOldAverage() {
        repeat(50) {
            val buffer = FloatArray(random.nextInt(1, 2000)) { random.nextFloat() * 2f - 1f }
            assertEquals(buffer.map { abs(it) }.average().toFloat(), dsp.audioLevel(buffer), 0f)
        }
        assertTrue(dsp.audioLevel(FloatArray(0)).isNaN())
        assertTrue(floatArrayOf().map { abs(it) }.average().toFloat().isNaN())
    }
}
