package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: when the clock switches to its night look, from the ambient light level. Plain
// Kotlin (NightRulesTest). The room must stay dark for a while before the night look comes on
// and bright for a while before it goes, and "bright" is well above "dark", so a passing shadow,
// a phone screen or the clock's own glow doesn't flip it back and forth.

data class NightState(
    val active: Boolean = false,
    /** Since when the light has been past the switching level (device clock, ms); null = it isn't. */
    val pendingSinceMs: Long? = null,
)

class NightRules(
    /** At or below this (lux) counts as dark. */
    val darkLux: Float,
    val enterAfterMs: Long = ENTER_AFTER_MS,
    val exitAfterMs: Long = EXIT_AFTER_MS,
) {
    /** At or above this counts as light again: three times the dark level, and at least 8 lux more. */
    val lightLux: Float = maxOf(darkLux * 3f, darkLux + 8f)

    /** The state after a reading of [lux] at [nowMs]. Call it on each reading and on a timer. */
    fun update(state: NightState, lux: Float, nowMs: Long): NightState {
        if (lux < 0f || lux.isNaN()) return state.copy(pendingSinceMs = null) // no reading yet
        val pastLevel = if (state.active) lux >= lightLux else lux <= darkLux
        if (!pastLevel) return state.copy(pendingSinceMs = null)
        val since = state.pendingSinceMs ?: nowMs
        val wait = if (state.active) exitAfterMs else enterAfterMs
        return if (nowMs - since >= wait) NightState(active = !state.active) else state.copy(pendingSinceMs = since)
    }

    companion object {
        const val ENTER_AFTER_MS = 60_000L
        const val EXIT_AFTER_MS = 10_000L

        /** Choices in the settings (lux). */
        val DARK_CHOICES = listOf(2f, 5f, 10f, 20f)
        const val DEFAULT_DARK_LUX = 5f

        fun darkLabel(lux: Float) = when (lux) {
            2f -> "Pitch dark"
            5f -> "Dark"
            10f -> "Night light"
            20f -> "Dim lamp"
            else -> "${lux.toInt()} lux"
        }
    }
}
