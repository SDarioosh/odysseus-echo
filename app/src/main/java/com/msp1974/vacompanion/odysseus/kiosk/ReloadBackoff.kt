package com.msp1974.vacompanion.odysseus.kiosk

// Odysseus fork: when to reload a dashboard that failed to load. Plain Kotlin, unit-tested
// (ReloadBackoffTest); DashboardAutoReload runs it. See docs/odysseus/kiosk.md.

/**
 * Waits [firstMs] before the first reload after a failure, then twice as long each time, up to
 * [maxMs]. Starts again from [firstMs] once the page loads, the network comes back, or the
 * dashboard is shown again.
 */
class ReloadBackoff(
    private val firstMs: Long = 5_000,
    private val maxMs: Long = 300_000,
) {
    private var attempts = 0
    private var networkWasUp = true

    /** The dashboard became visible (or the screen came back on): retry soon. */
    fun onShown() {
        attempts = 0
    }

    /** Called on every page or network change before [nextDelayMs]. */
    fun observe(loaded: Boolean, networkUp: Boolean) {
        if (loaded || (networkUp && !networkWasUp)) attempts = 0
        networkWasUp = networkUp
    }

    /**
     * How long to wait before the next reload, or null for no reload: the feature is off, the
     * page didn't fail, or there is no network (a reload can't work then; the network coming
     * back starts the retries again).
     */
    fun nextDelayMs(failed: Boolean, networkUp: Boolean, enabled: Boolean): Long? {
        if (!enabled || !failed || !networkUp) return null
        val delay = (firstMs shl attempts.coerceAtMost(MAX_SHIFT)).coerceAtMost(maxMs)
        attempts++
        return delay
    }

    private companion object {
        const val MAX_SHIFT = 16
    }
}
