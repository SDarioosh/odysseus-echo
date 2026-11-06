package com.msp1974.vacompanion.odysseus.record

import com.msp1974.vacompanion.odysseus.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChunkAssemblerTest {

    private val start = 1_762_420_500_000L   // 2025-11-06T09:15:00Z
    private val frame = 1280                 // 80 ms at 16 kHz

    private fun frameOf(value: Short) = ShortArray(frame) { value }

    @Test
    fun thirtySecondsMakeOneChunk() {
        val a = ChunkAssembler(start)
        val framesPerChunk = ChunkAssembler.CHUNK_SAMPLES / frame   // 375
        assertEquals(375, framesPerChunk)
        repeat(framesPerChunk - 1) { assertTrue(a.add(frameOf(1)).isEmpty()) }
        val done = a.add(frameOf(1))
        assertEquals(1, done.size)
        assertEquals(0, done[0].sequence)
        assertEquals(start, done[0].startMs)
        assertEquals(ChunkAssembler.CHUNK_SAMPLES, done[0].pcm.size)
    }

    @Test
    fun eachChunkStartsAt80MsTimesTheFramesBeforeIt() {
        val a = ChunkAssembler(start)
        val chunks = mutableListOf<AudioChunk>()
        repeat(375 * 2 + 10) { chunks += a.add(frameOf(it.toShort())) }
        a.finish()?.let { chunks += it }
        assertEquals(listOf(0, 1, 2), chunks.map { it.sequence })
        assertEquals(listOf(start, start + 375 * 80L, start + 750 * 80L), chunks.map { it.startMs })
        assertEquals(10 * frame, chunks[2].pcm.size)
        // Nothing lost or repeated at the joins.
        assertEquals(374.toShort(), chunks[0].pcm.last())
        assertEquals(375.toShort(), chunks[1].pcm.first())
    }

    @Test
    fun aFrameAcrossTheBoundaryIsSplit() {
        // A chunk of 1000 samples and frames of 1280: the frame's tail starts the next chunk.
        val a = ChunkAssembler(start, chunkSamples = 1000)
        val frameData = ShortArray(1280) { it.toShort() }
        val done = a.add(frameData)
        assertEquals(1, done.size)
        val last = a.finish()!!
        assertEquals(280, last.pcm.size)
        assertEquals(1000.toShort(), last.pcm[0])
        assertEquals(start + 1000 * 1000L / 16_000, last.startMs)
    }

    @Test
    fun finishWithNothingLeftIsNull() {
        val a = ChunkAssembler(start)
        assertNull(a.finish())
        assertEquals(0, a.chunks)
    }

    @Test
    fun timestampsAreIsoUtcWithMilliseconds() {
        assertEquals("2025-11-06T09:15:00.000Z", ChunkAssembler.isoUtc(start))
        assertEquals("2025-11-06T09:15:30.080Z", ChunkAssembler.isoUtc(start + 30_080))
    }

    /** The hub relay's rule for codec=wav (H4): a plain PCM header, mono, 16 kHz, 16 bits, fmt before data. */
    @Test
    fun chunkWavHasThePlainPcmHeaderTheHubAccepts() {
        val pcm = ShortArray(ChunkAssembler.CHUNK_SAMPLES) { (it % 100).toShort() }
        val wav = Wav.encodePcm16Mono(pcm, ChunkAssembler.SAMPLE_RATE)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt())          // format 1 = PCM (not EXTENSIBLE)
        assertEquals(1, b.getShort(22).toInt())          // 1 channel
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28))               // byte rate
        assertEquals(2, b.getShort(32).toInt())          // block align
        assertEquals(16, b.getShort(34).toInt())         // bits
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size * 2, b.getInt(40))
        // 30 s is 960 KB, under the relay's 5 MiB limit.
        assertEquals(44 + 960_000, wav.size)
        assertTrue(wav.size < 5 * 1024 * 1024)
    }
}
