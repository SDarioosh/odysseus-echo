package com.msp1974.vacompanion.odysseus.arbitration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class WakeLevelMeterTest {

    private val rate = 16000
    private val frameSamples = 1280 // 80 ms, as the microphone reads

    private var seed = 7L
    private fun gaussian(): Float {
        var sum = 0f
        repeat(4) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            sum += ((seed ushr 33).toInt() / 2147483648f) - 0.5f
        }
        return sum / sqrt(4f / 12f)
    }

    private var phase = 0.0

    /** [seconds] of a sine at [hz] with RMS [levelDbfs], plus [dc] (a fraction of full scale). */
    private fun WakeLevelMeter.tone(seconds: Float, hz: Double, levelDbfs: Float, dc: Float = 0f) {
        val amplitude = 10f.pow(levelDbfs / 20f) * sqrt(2f)
        var remaining = (seconds * rate).toInt()
        while (remaining > 0) {
            val n = minOf(frameSamples, remaining)
            update(ShortArray(n) {
                phase += 2 * PI * hz / rate
                ((amplitude * sin(phase) + dc) * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }, rate)
            remaining -= n
        }
    }

    private fun WakeLevelMeter.noise(seconds: Float, levelDbfs: Float) {
        val amplitude = 10f.pow(levelDbfs / 20f)
        var remaining = (seconds * rate).toInt()
        while (remaining > 0) {
            val n = minOf(frameSamples, remaining)
            update(ShortArray(n) { (gaussian() * amplitude * 32768f).toInt().coerceIn(-32768, 32767).toShort() }, rate)
            remaining -= n
        }
    }

    @Test
    fun unknownUntilAnyAudioHasBeenHeard() {
        assertNull(WakeLevelMeter().wakeWordDbfs())
        assertNull(WakeLevelMeter().noiseFloorDbfs())
    }

    @Test
    fun readsSpeechBandAudioAtItsLevel() {
        val m = WakeLevelMeter()
        m.tone(1.5f, 1000.0, -30f)
        assertEquals(-30f, m.wakeWordDbfs()!!, 0.5f)
    }

    @Test
    fun hum_rumble_andDcOffsetBarelyCount() {
        val m = WakeLevelMeter()
        m.tone(1.5f, 50.0, -20f, dc = 0.05f)
        assertTrue("read ${m.wakeWordDbfs()}", m.wakeWordDbfs()!! < -40f)
    }

    @Test
    fun aVoiceOverLoudRumbleReadsAsTheVoice() {
        // A fridge or music's bass near one device would have decided upstream's broadband peak.
        val withRumble = WakeLevelMeter().apply {
            val n = (1.5f * rate).toInt()
            var i = 0
            while (i < n) {
                val len = minOf(frameSamples, n - i)
                update(ShortArray(len) { k ->
                    val t = (i + k).toDouble() / rate
                    ((0.0447 * sin(2 * PI * 1000 * t) + 0.3 * sin(2 * PI * 60 * t)) * 32767).toInt().toShort()
                }, rate)
                i += len
            }
        }
        assertEquals(-30f, withRumble.wakeWordDbfs()!!, 1f)
    }

    @Test
    fun aSingleLoudFrameDoesNotDecideOnItsOwn() {
        val m = WakeLevelMeter()
        m.noise(1.0f, -40f)
        m.noise(0.08f, -10f) // a tap on the device
        m.noise(0.42f, -40f)
        val level = m.wakeWordDbfs()!!
        // The power average of three frames: well under the click's -10.
        assertTrue("read $level", level < -13.5f)
    }

    @Test
    fun theLoudestPartOfTheWakeWordIsWhatCounts() {
        val m = WakeLevelMeter()
        m.noise(0.6f, -60f)
        m.noise(0.6f, -30f) // "Hey Athena"
        m.noise(0.3f, -60f)
        assertEquals(-30f, m.wakeWordDbfs()!!, 1.5f)
    }

    @Test
    fun audioFromBeforeTheWakeWordIsNotCounted() {
        val m = WakeLevelMeter()
        m.noise(1.0f, -20f) // the TV, a moment before
        m.noise(1.6f, -45f)
        assertTrue("read ${m.wakeWordDbfs()}", m.wakeWordDbfs()!! < -40f)
    }

    @Test
    fun theNoiseFloorIsTheQuietBackgroundNotTheWakeWord() {
        val m = WakeLevelMeter()
        m.noise(3.5f, -55f)
        m.noise(1.0f, -25f)
        assertEquals(-55f, m.noiseFloorDbfs()!!, 2f)
    }

    @Test
    fun resetForgetsEverything() {
        val m = WakeLevelMeter()
        m.noise(1f, -30f)
        m.reset()
        assertNull(m.wakeWordDbfs())
    }

    @Test
    fun aCalibratedReadingAddsTheOffsetToBoth() {
        val m = WakeLevelMeter()
        m.noise(3f, -50f)
        m.noise(1f, -30f)
        val reading = WakeLevelReading.of(m, offsetDb = 4f)
        assertEquals(m.wakeWordDbfs()!! + 4f, reading.levelDb!!, 0.001f)
        assertEquals(m.noiseFloorDbfs()!! + 4f, reading.floorDb!!, 0.001f)
        assertNull(WakeLevelReading.of(WakeLevelMeter(), 4f).levelDb)
    }
}
