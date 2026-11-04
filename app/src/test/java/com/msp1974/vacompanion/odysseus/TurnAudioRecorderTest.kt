package com.msp1974.vacompanion.odysseus

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TurnAudioRecorderTest {

    private fun le(wav: ByteArray) = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun wavHeaderDescribes16kMono16Bit() {
        val wav = Wav.encodePcm16Mono(shortArrayOf(1, -1, 32767, -32768), 16_000)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        val b = le(wav)
        assertEquals(36 + 8, b.getInt(4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt())      // PCM
        assertEquals(1, b.getShort(22).toInt())      // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8, b.getInt(40))
        assertEquals(1, b.getShort(44).toInt())
        assertEquals(-1, b.getShort(46).toInt())
        assertEquals(32767, b.getShort(48).toInt())
        assertEquals(-32768, b.getShort(50).toInt())
    }

    @Test
    fun keepsOnlyTheFirstMaxSeconds() {
        val r = TurnAudioRecorder(sampleRate = 100, maxSeconds = 2) // 200 samples
        repeat(5) { i -> r.add(ShortArray(80) { i.toShort() }) }
        assertEquals(200, r.samples)
        val wav = r.finish()!!
        assertEquals(44 + 400, wav.size)
        // The first frames are kept, the last ones dropped.
        assertEquals(0, le(wav).getShort(44).toInt())
        assertEquals(2, le(wav).getShort(44 + 2 * 199).toInt())
    }

    @Test
    fun nothingAfterFinishOrDiscard() {
        val r = TurnAudioRecorder(sampleRate = 100)
        assertNull(r.finish())
        r.add(ShortArray(10))
        assertEquals(0, r.samples)

        val d = TurnAudioRecorder(sampleRate = 100)
        d.add(ShortArray(10))
        d.discard()
        d.add(ShortArray(10))
        assertNull(d.finish())
    }

    @Test
    fun teePassesFramesThroughUnchanged() = runBlocking {
        val r = TurnAudioRecorder(sampleRate = 100)
        val frames = listOf(shortArrayOf(1, 2), shortArrayOf(3))
        val out = r.tee(flowOf(*frames.toTypedArray())).toList()
        assertEquals(2, out.size)
        assertArrayEquals(frames[0], out[0])
        assertEquals(3, r.samples)
    }
}
