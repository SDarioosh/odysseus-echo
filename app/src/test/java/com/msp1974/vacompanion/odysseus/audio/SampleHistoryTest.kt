package com.msp1974.vacompanion.odysseus.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import kotlin.random.Random

/** [SampleHistory] must keep exactly what openWakeWord's old ArrayDeque<Float> history kept. */
class SampleHistoryTest {

    /** The old code (AudioProcessor.bufferRawData / streamingMelSpectrogram), as it was. */
    private class DequeHistory(private val capacity: Int) {
        val deque = ArrayDeque<Float>(capacity)
        fun append(data: FloatArray) {
            while (deque.size + data.size > capacity) deque.poll()
            data.forEach { deque.offer(it) }
        }
        fun last(n: Int): FloatArray = deque.toList().takeLast(n).toFloatArray()
    }

    private fun frame(random: Random, size: Int) = FloatArray(size) { random.nextFloat() * 65536f - 32768f }

    @Test
    fun matchesTheOldDequeForOpenWakeWordFrames() {
        // The real sizes: 10 s at 16 kHz, 1280-sample frames, read 1280 + 480 back.
        val random = Random(1)
        val old = DequeHistory(160_000)
        val new = SampleHistory(160_000)
        repeat(400) { // 32 s: fills, then wraps the ring several times
            val f = frame(random, 1280)
            old.append(f)
            new.append(f)
            assertEquals(old.deque.size, new.size)
            assertArrayEquals(old.last(1760), new.last(1760), 0f)
        }
        assertArrayEquals(old.last(160_000), new.last(160_000), 0f)
    }

    @Test
    fun matchesTheOldDequeForAnyFrameSizeUpToCapacity() {
        val random = Random(2)
        for (capacity in listOf(1, 7, 100, 1000)) {
            val old = DequeHistory(capacity)
            val new = SampleHistory(capacity)
            repeat(500) {
                val f = frame(random, random.nextInt(0, capacity + 1))
                old.append(f)
                new.append(f)
                assertEquals(old.deque.size, new.size)
                val n = random.nextInt(0, capacity + 5)
                assertArrayEquals("capacity $capacity, n $n", old.last(n), new.last(n), 0f)
            }
        }
    }

    @Test
    fun aFrameLargerThanCapacityKeepsItsNewestSamples() {
        val history = SampleHistory(4)
        history.append(floatArrayOf(1f, 2f))
        history.append(floatArrayOf(3f, 4f, 5f, 6f, 7f, 8f))
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 8f), history.last(10), 0f)
    }

    @Test
    fun clearEmptiesIt() {
        val history = SampleHistory(4)
        history.append(floatArrayOf(1f, 2f, 3f))
        history.clear()
        assertEquals(0, history.size)
        assertEquals(0, history.last(3).size)
        history.append(floatArrayOf(9f))
        assertArrayEquals(floatArrayOf(9f), history.last(3), 0f)
    }
}

class PlusKeepingLastTest {
    private fun rows(vararg ids: Int) = Array(ids.size) { floatArrayOf(ids[it].toFloat()) }

    @Test
    fun matchesConcatenateThenTakeLast() {
        for (have in 0..6) for (add in 0..4) for (max in 1..8) {
            val a = rows(*IntArray(have) { it })
            val b = rows(*IntArray(add) { 100 + it })
            val joined = a + b
            val expected = if (joined.size > max) joined.takeLast(max).toTypedArray() else joined
            val actual = a.plusKeepingLast(b, max)
            assertEquals(expected.size, actual.size)
            // The same row objects, in the same order.
            expected.indices.forEach { assertTrue(expected[it] === actual[it]) }
        }
    }
}
