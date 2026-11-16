package com.msp1974.vacompanion.odysseus.wyoming

import com.msp1974.vacompanion.wyoming.WyomingEvent

/**
 * How long the device waits for the next message on a Wyoming connection before it treats
 * Home Assistant as gone and closes the socket.
 *
 * Home Assistant keeps the connection busy itself: it pings the satellite 2 s after each pong
 * and drops the connection after 5 s without any event from it (`_PING_SEND_DELAY`,
 * `_PING_TIMEOUT` in homeassistant/components/wyoming/assist_satellite.py, since 2024.2). A
 * live connection is therefore never silent for the watchdog time, and the device doesn't
 * ping Home Assistant itself.
 *
 * After `pause-satellite` (the satellite muted in Home Assistant) Home Assistant stops reading
 * and pinging on purpose but keeps the socket open, so silence is expected and there is no
 * limit until the next message. The server's pause timeout stops the satellite (and closes
 * the socket) if no `run-satellite` follows.
 */
object WyomingSilencePolicy {

    /** True when [type] means Home Assistant will now be silent on purpose. */
    fun silenceExpectedAfter(type: String): Boolean = type == WyomingEvent.PAUSE_SATELLITE

    /**
     * How long the next read may wait, in ms, counted from the last message
     * ([lastMessageAt]) as the old 1 s watchdog loop did; 0 = give up now; null = no limit.
     */
    fun readLimitMs(lastMessageAt: Long, now: Long, timeoutMs: Long, silenceExpected: Boolean): Long? {
        if (silenceExpected) return null
        return (lastMessageAt + timeoutMs - now).coerceAtLeast(0L)
    }
}
