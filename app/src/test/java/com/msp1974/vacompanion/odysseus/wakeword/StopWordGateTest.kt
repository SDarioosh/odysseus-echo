package com.msp1974.vacompanion.odysseus.wakeword

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class StopWordGateTest {

    /** A result as MicroWakeWord gives it: whether the window's mean beat the cutoff, and the probability. */
    private data class Result(val detected: Boolean, val score: Float)

    /**
     * Built like a microWakeWord model: collects [stride] frames per inference, keeps the last
     * [memory] frames as its streaming state (and nothing older), and reports the mean of its last
     * [window] probabilities against a cutoff. Starts from zero state, like a fresh interpreter.
     */
    private class FakeStreamingModel(val stride: Int = 3, val window: Int = 5, val memory: Int = 90) {
        private val pending = ArrayList<FloatArray>()
        private val state = ArrayDeque<FloatArray>().apply { repeat(memory) { add(FloatArray(FEATURES)) } }
        private val probabilities = ArrayDeque<Float>()
        var inferences = 0

        fun process(features: FloatArray): Result {
            pending.add(features.copyOf())
            if (pending.size < stride) return Result(false, 0f)
            inferences++
            var acc = 0.0
            // Every frame of the state and the new stride counts, with its own weight by position.
            val seen = state + pending
            seen.forEachIndexed { i, frame -> frame.forEachIndexed { j, v -> acc += v * ((i * 31 + j * 7) % 13 - 6) } }
            val probability = (1.0 / (1.0 + exp(-acc / 400.0))).toFloat()
            for (frame in pending) { state.removeFirst(); state.addLast(frame) }
            pending.clear()
            if (probabilities.size == window) probabilities.removeFirst()
            probabilities.addLast(probability)
            return Result(probabilities.size == window && probabilities.average() > 0.5, probability)
        }
    }

    private fun frames(count: Int, seed: Int = 1): List<FloatArray> {
        val random = Random(seed)
        return List(count) { FloatArray(FEATURES) { random.nextFloat() * 26f } }
    }

    /** Whether the stop model is wanted on each frame: random stretches on and off, any length. */
    private fun wantedPattern(count: Int, seed: Int = 2): BooleanArray {
        val random = Random(seed)
        val wanted = BooleanArray(count)
        var i = 0
        var on = false
        while (i < count) {
            val length = 1 + random.nextInt(if (on) 60 else 400)
            for (k in i until minOf(count, i + length)) wanted[k] = on
            i += length
            on = !on
        }
        return wanted
    }

    @Test
    fun gatedScoresAreTheScoresOfAModelThatRanAllAlong() {
        for (seed in 1..20) {
            val input = frames(6000, seed)
            val wanted = wantedPattern(input.size, seed + 100)
            val continuous = FakeStreamingModel().let { model -> input.map { model.process(it) } }
            val model = FakeStreamingModel()
            val gate = StopWordGate(stride = 3, window = 5, memoryFrames = 90) { model.process(it) }

            var compared = 0
            input.forEachIndexed { i, frame ->
                val result = gate.onFrame(frame, wanted[i])
                if (wanted[i]) {
                    assertEquals("seed $seed frame $i", continuous[i], result)
                    compared++
                }
            }
            assertTrue(compared > 500)
            // Toggled every few seconds here, so each warm-up's replay is a large share; still fewer.
            assertTrue("fewer inferences", model.inferences < input.size / 3)
        }
    }

    @Test
    fun mostlyNothingToStopCostsLittle() {
        // 10 minutes idle, a 30 s alarm, 10 minutes idle: about 6 % of the continuous inferences.
        val input = frames(60_000 + 3_000 + 60_000)
        val model = FakeStreamingModel()
        val gate = StopWordGate(stride = 3, window = 5) { model.process(it) }
        input.forEachIndexed { i, frame -> gate.onFrame(frame, wanted = i in 60_000 until 63_000) }
        assertTrue(model.inferences < input.size / 3 / 10)
    }

    @Test
    fun theDefaultMemoryCoversTheModel() {
        val input = frames(4000)
        val wanted = wantedPattern(input.size)
        val continuous = FakeStreamingModel().let { model -> input.map { model.process(it) } }
        val model = FakeStreamingModel()
        val gate = StopWordGate(stride = 3, window = 5) { model.process(it) }
        input.forEachIndexed { i, frame ->
            val result = gate.onFrame(frame, wanted[i])
            if (wanted[i]) assertEquals(continuous[i], result)
        }
    }

    @Test
    fun aWarmUpShorterThanTheModelsMemoryWouldNotMatch() {
        // The check above is sensitive: too short a history leaves stale state in the model.
        val input = frames(4000)
        val wanted = wantedPattern(input.size)
        val continuous = FakeStreamingModel().let { model -> input.map { model.process(it) } }
        val model = FakeStreamingModel()
        val gate = StopWordGate(stride = 3, window = 5, memoryFrames = 20) { model.process(it) }
        val mismatches = input.indices.count { i ->
            val result = gate.onFrame(input[i], wanted[i])
            wanted[i] && result != continuous[i]
        }
        assertNotEquals(0, mismatches)
    }

    @Test
    fun runsThroughStartUpThenPausesOnlyBetweenInferences() {
        val model = FakeStreamingModel()
        val gate = StopWordGate(stride = 3, window = 5, memoryFrames = 90) { model.process(it) }
        assertEquals(90 + 3 * 6, gate.historyFrames)
        val input = frames(400)
        // Never wanted: it still runs until the history is full, so a later warm-up is complete.
        for (i in 0 until gate.historyFrames - 1) {
            assertTrue(gate.onFrame(input[i], wanted = false) != null)
        }
        gate.onFrame(input[gate.historyFrames - 1], wanted = false)
        assertFalse(gate.running)
        assertEquals(gate.historyFrames / 3, model.inferences)
        // Paused: nothing reported and no inference.
        for (i in gate.historyFrames until 300) assertNull(gate.onFrame(input[i], wanted = false))
        assertEquals(gate.historyFrames / 3, model.inferences)
    }

    @Test
    fun aPauseWaitsForTheStrideToComplete() {
        val model = FakeStreamingModel()
        val gate = StopWordGate(stride = 3, window = 5, memoryFrames = 90) { model.process(it) }
        val input = frames(400)
        for (i in 0 until 200) gate.onFrame(input[i], wanted = true)
        // 200 frames in: two frames of the next inference are pending, so it keeps going one more.
        assertTrue(gate.onFrame(input[200], wanted = false) != null)
        assertFalse(gate.running)
        assertEquals(67, model.inferences)
    }

    @Test
    fun resumingReplaysTheHistoryOnAStrideBoundary() {
        val model = FakeStreamingModel()
        val gate = StopWordGate(stride = 3, window = 5, memoryFrames = 90) { model.process(it) }
        val input = frames(400)
        for (i in 0 until 301) gate.onFrame(input[i], wanted = i < 150)
        assertFalse(gate.running)
        // 301 frames seen; the oldest held is frame 193, so the replay starts at frame 195.
        gate.onFrame(input[301], wanted = true)
        assertTrue(gate.running)
        assertEquals(301 - 195, gate.lastWarmUpFrames)
    }

    @Test
    fun historyKeepsTheLastFramesInOrderAsCopies() {
        val history = FeatureHistory(4)
        val reused = FloatArray(2)
        for (v in 1..6) {
            reused[0] = v.toFloat(); reused[1] = -v.toFloat()
            history.add(reused)
        }
        assertEquals(4, history.size)
        val seen = mutableListOf<FloatArray>()
        history.forEachLast(4) { seen.add(it.copyOf()) }
        assertEquals(listOf(3f, 4f, 5f, 6f), seen.map { it[0] })
        assertArrayEquals(floatArrayOf(6f, -6f), seen.last(), 0f)
        val lastTwo = mutableListOf<Float>()
        history.forEachLast(2) { lastTwo.add(it[0]) }
        assertEquals(listOf(5f, 6f), lastTwo)
    }

    @Test
    fun stopIsUsefulWhileAReplyStreamsOrAnAlarmSounds() {
        assertFalse(StopWordRules.useful(ttsStreaming = false, alarmSounding = false))
        assertTrue(StopWordRules.useful(ttsStreaming = true, alarmSounding = false))
        assertTrue(StopWordRules.useful(ttsStreaming = false, alarmSounding = true))
        assertTrue(StopWordRules.useful(ttsStreaming = true, alarmSounding = true))
    }

    private companion object {
        const val FEATURES = 40
    }
}
