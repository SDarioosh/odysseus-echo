package com.msp1974.vacompanion.odysseus.power

/**
 * Why this device wants Wi-Fi out of power save right now. Power save makes the access point hold
 * frames for the device until it next wakes (a beacon, 100-300 ms, longer with the screen off), so
 * it is only switched off while something here needs low latency (docs/odysseus/power.md).
 *
 * [graceMs]: how long the lock stays after the need ends, so a need that ends and comes straight
 * back (a continued conversation, the next track) doesn't flap the radio.
 * [maxMs]: a need that has been on this long without being set again is a bug; it is dropped.
 */
enum class WifiNeed(val graceMs: Long, val maxMs: Long) {
    /**
     * A voice interaction: from the wake word (before arbitration, so the master's decision isn't
     * held at the access point) through the pipeline, HA's answer and its speech.
     */
    VOICE(graceMs = 5_000, maxMs = 10 * 60_000),

    /**
     * This device is the arbitration master and other devices are around: their wake word
     * reports must reach it within the 150 ms decision window (docs/wake-word-arbitration.md).
     */
    ARBITRATION_MASTER(graceMs = 0, maxMs = Long.MAX_VALUE),

    /** A Sendspin stream is playing: its clock sync needs steady round trips to the server. */
    SENDSPIN(graceMs = 5_000, maxMs = Long.MAX_VALUE),

    /** Someone is watching the camera (RTSP): live video. */
    CAMERA(graceMs = 0, maxMs = Long.MAX_VALUE),
}

/** The platform Wi-Fi lock modes this app uses. */
enum class WifiLockMode { HIGH_PERF, LOW_LATENCY }

/**
 * Whether to hold the Wi-Fi lock: while any [WifiNeed] is on, or within its grace after it ended.
 * Plain Kotlin, driven by [WifiPerformance]; times are any monotonic clock in milliseconds.
 */
class WifiLockPolicy {
    // need -> since when it is on
    private val on = HashMap<WifiNeed, Long>()
    // need -> until when it lingers after being switched off
    private val lingering = HashMap<WifiNeed, Long>()

    /** Turns [need] on or off at [now]. Turning on again refreshes its [WifiNeed.maxMs]. */
    @Synchronized
    fun set(need: WifiNeed, active: Boolean, now: Long) {
        if (active) {
            on[need] = now
            lingering.remove(need)
        } else if (on.remove(need) != null && need.graceMs > 0) {
            lingering[need] = now + need.graceMs
        }
    }

    /** The needs holding the lock at [now]: those on (within their limit) and those lingering. */
    @Synchronized
    fun reasons(now: Long): Set<WifiNeed> {
        on.entries.removeIf { (need, since) -> now - since >= need.maxMs }
        lingering.entries.removeIf { (_, until) -> now >= until }
        return on.keys + lingering.keys
    }

    @Synchronized
    fun shouldHold(now: Long): Boolean = reasons(now).isNotEmpty()

    /** When [reasons] next changes by itself (a grace or a limit running out), or null if never. */
    @Synchronized
    fun nextChangeAt(now: Long): Long? {
        reasons(now)
        val limits = on.mapNotNull { (need, since) ->
            if (need.maxMs == Long.MAX_VALUE) null else since + need.maxMs
        }
        return (limits + lingering.values).minOrNull()
    }

    companion object {
        /**
         * The locks to hold, by Android version ([sdkInt]):
         * - 26-28: high-performance only (no low-latency mode before Android 10).
         * - 29 and up: both. The platform uses the strongest one that applies: low latency while
         *   the screen is on and the app is visible, high performance otherwise (screen off, or
         *   the app behind another one). Both switch power save off; low latency also asks the
         *   chip for its low-latency mode where it has one. From Android 14 the platform may treat
         *   a high-performance lock as a low-latency one.
         */
        fun lockModes(sdkInt: Int): List<WifiLockMode> =
            if (sdkInt >= 29) listOf(WifiLockMode.HIGH_PERF, WifiLockMode.LOW_LATENCY)
            else listOf(WifiLockMode.HIGH_PERF)
    }
}
