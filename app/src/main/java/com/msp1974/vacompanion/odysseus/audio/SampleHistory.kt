package com.msp1974.vacompanion.odysseus.audio

/**
 * Odysseus fork (docs/odysseus/power.md): the newest [capacity] audio samples, in a plain
 * [FloatArray] ring.
 *
 * Replaces openWakeWord's `ArrayDeque<Float>` history, which boxed every sample and copied all
 * 160,000 of them into a list on every 80 ms frame only to read the last ~1,760. This keeps
 * exactly the same samples: [append] drops the oldest ones so at most [capacity] are kept
 * (the deque did the same for any frame up to [capacity] samples, and hung forever on a larger
 * one; here a larger frame keeps its newest [capacity] samples), and [last] returns the newest
 * `n` in order, like `toList().takeLast(n)`.
 */
class SampleHistory(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val ring = FloatArray(capacity)
    private var start = 0   // index of the oldest sample
    var size = 0
        private set

    fun append(data: FloatArray) {
        val count = data.size
        if (count >= capacity) {
            // Only the newest `capacity` samples survive.
            System.arraycopy(data, count - capacity, ring, 0, capacity)
            start = 0
            size = capacity
            return
        }
        // Drop the oldest samples that no longer fit.
        val overflow = size + count - capacity
        if (overflow > 0) {
            start = (start + overflow) % capacity
            size -= overflow
        }
        // Write after the newest sample, wrapping at the end of the ring.
        val end = (start + size) % capacity
        val firstPart = minOf(count, capacity - end)
        System.arraycopy(data, 0, ring, end, firstPart)
        if (firstPart < count) System.arraycopy(data, firstPart, ring, 0, count - firstPart)
        size += count
    }

    /** The newest `min(n, size)` samples, oldest first. */
    fun last(n: Int): FloatArray {
        val count = n.coerceIn(0, size)
        val out = FloatArray(count)
        if (count == 0) return out
        val from = (start + size - count) % capacity
        val firstPart = minOf(count, capacity - from)
        System.arraycopy(ring, from, out, 0, firstPart)
        if (firstPart < count) System.arraycopy(ring, 0, out, firstPart, count - firstPart)
        return out
    }

    fun clear() {
        start = 0
        size = 0
    }
}

/**
 * For openWakeWord's feature and mel buffers: `(this + more).takeLast(max).toTypedArray()` when the
 * result would be longer than [max], else `this + more`, in one array copy. The rows themselves
 * are shared, not copied, exactly as before.
 */
fun Array<FloatArray>.plusKeepingLast(more: Array<FloatArray>, max: Int): Array<FloatArray> {
    val total = size + more.size
    if (total <= max) return this + more
    val drop = total - max
    return Array(max) { i -> val j = i + drop; if (j < size) this[j] else more[j - size] }
}
