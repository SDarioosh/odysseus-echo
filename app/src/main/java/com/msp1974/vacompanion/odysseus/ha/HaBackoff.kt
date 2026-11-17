package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: the wait before the websocket's next connection attempt: 1, 2, 4, 8, 16, 30,
// 30 … seconds, back to 1 s after a connection that got as far as auth_ok. Pure (HaBackoffTest).

class HaBackoff(private val firstMs: Long = 1_000L, private val maxMs: Long = 30_000L) {
    private var failures = 0

    /** The wait before the next attempt; each call counts one more failure. */
    fun nextDelayMs(): Long {
        val shift = failures.coerceAtMost(20)
        failures++
        return (firstMs shl shift).coerceAtMost(maxMs)
    }

    fun reset() {
        failures = 0
    }
}
