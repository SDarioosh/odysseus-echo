package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork (docs/odysseus/power.md, "Stop word model only while something can be stopped"):
// plain Kotlin, no TFLite, tested in StopWordGateTest.

/**
 * When a "stop" can do anything: what Satellite.handleStopWord acts on. Keep the two in step.
 *
 * - a response is being spoken: the pipeline is streaming TTS (an Assist answer, or an
 *   announcement, which runs as a pipeline that starts at STREAMING_TTS);
 * - an alarm is sounding.
 *
 * Nothing else reacts to a "stop": the STOP_WORD_DETECTED broadcast has no receiver, and music,
 * Sendspin and sound effects never listened for it.
 */
object StopWordRules {
    fun useful(ttsStreaming: Boolean, alarmSounding: Boolean): Boolean = ttsStreaming || alarmSounding
}

/**
 * The last [capacity] feature frames, oldest first. Its slots are allocated once (on the first
 * frame, sized to it) and the features are copied in, so the caller may reuse its array.
 */
class FeatureHistory(val capacity: Int) {
    private var slots: Array<FloatArray>? = null
    private var next = 0

    /** Frames held, at most [capacity]. */
    var size = 0
        private set

    fun add(features: FloatArray) {
        val ring = slots?.takeIf { it[0].size == features.size }
            ?: Array(capacity) { FloatArray(features.size) }.also { slots = it; next = 0; size = 0 }
        features.copyInto(ring[next])
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** The [count] most recent frames, oldest first. The arrays are the ring's own: read, don't keep. */
    fun forEachLast(count: Int, action: (FloatArray) -> Unit) {
        val ring = slots ?: return
        require(count in 0..size)
        for (i in count downTo 1) action(ring[(next - i + capacity) % capacity])
    }
}

/**
 * Runs one streaming word model (microWakeWord's "stop") only while it is [wanted][onFrame], and
 * makes it, whenever it runs, exactly as if it had run all along.
 *
 * A microWakeWord model takes [stride] feature frames (10 ms each) per inference and keeps, in its
 * own variables, a ring of what it saw: its state is a function of the last [memoryFrames] frames
 * and nothing older. Its score is the mean of its last [window] outputs. So:
 *
 * - Every frame goes into a [FeatureHistory] of [historyFrames] = memory + stride × (window + 1).
 * - The model is paused only between two inferences (its input buffer empty), never mid-stride,
 *   and never in its first [historyFrames] frames: by the time it can pause, the history is full.
 * - When it is wanted again, the history is replayed through it first (outputs discarded),
 *   starting on a stride boundary of the continuous run. That overwrites every bit of its state
 *   and its whole score window with what a model that had never paused would hold, so the frame
 *   that resumes it gets the very score continuous running gives (StopWordGateTest checks this).
 *
 * Single-threaded: call it only from the detection loop. [wanted] is read by that loop each
 * frame; whoever changes what it reads doesn't touch this object.
 */
class StopWordGate<R>(
    /** Feature frames per inference (the model's input shape [1, stride, features]). */
    val stride: Int,
    /** Inferences averaged into the score (the model's sliding_window_size). */
    window: Int,
    /** Frames the model's internal state depends on. */
    memoryFrames: Int = STOP_MODEL_MEMORY_FRAMES,
    /** One frame in; the model's result (it runs an inference every [stride] frames). */
    private val process: (FloatArray) -> R,
) {
    init {
        require(stride >= 1 && window >= 1 && memoryFrames >= 0)
    }

    val historyFrames = memoryFrames + stride * (window + 1)
    private val history = FeatureHistory(historyFrames)
    private var framesSeen = 0L

    /** The model is being fed (false: paused at a stride boundary). */
    var running = true
        private set

    /** Frames replayed when it last resumed (for the log). */
    var lastWarmUpFrames = 0
        private set

    /**
     * One feature frame. Returns the model's result for it, or null while the model is paused
     * (nothing could be stopped, so no score is needed).
     */
    fun onFrame(features: FloatArray, wanted: Boolean): R? {
        if (!running && wanted) resume()
        val result = if (running) process(features) else null
        history.add(features)
        framesSeen++
        if (running && !wanted && framesSeen >= historyFrames && framesSeen % stride == 0L) {
            running = false
        }
        return result
    }

    private fun resume() {
        // Paused at a stride boundary, so the model's input buffer is empty. Replay from the oldest
        // stride boundary still in the history: the model then holds the same partial stride as a
        // model that never paused, and the frame being handled lands where it would have.
        val oldest = framesSeen - history.size
        val skip = ((stride - oldest % stride) % stride).toInt()
        val count = history.size - skip
        history.forEachLast(count) { process(it) }
        lastWarmUpFrames = count
        running = true
    }

    companion object {
        /**
         * The bundled stop model (assets/microwakeword/stopWords/stop.tflite, input [1, 3, 40]):
         * its streaming layers keep 2 frames, then 4, 8, 12 and 5 inferences, which is
         * 2 + 3 × (4 + 8 + 12 + 5) = 89 frames; replaying 90 frames into a model holding
         * unrelated state gave bit-identical state and outputs (checked with the TFLite
         * interpreter). 120 (1.2 s) leaves room for a slightly larger stop model.
         */
        const val STOP_MODEL_MEMORY_FRAMES = 120
    }
}
