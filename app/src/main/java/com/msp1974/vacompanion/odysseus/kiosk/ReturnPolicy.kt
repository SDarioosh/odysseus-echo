package com.msp1974.vacompanion.odysseus.kiosk

// Odysseus fork: when to bring the app back after another app came in front. Plain Kotlin,
// unit-tested (ReturnPolicyTest); ReturnToDashboard runs it. See docs/odysseus/kiosk.md.

object ReturnPolicy {
    /** While the screen is off, how often to look again. */
    const val SCREEN_OFF_RECHECK_MS = 30_000L

    sealed interface Action {
        /** Look again after [ms]. */
        data class Wait(val ms: Long) : Action
        /** Bring the app to the front now. */
        data object Return : Action
        /** Nothing to do: the feature is off. */
        data object Never : Action
    }

    /**
     * [hiddenSinceMs]: when the app left the front (same clock as [nowMs]); [timeoutMs]: how long
     * another app may stay in front, 0 or less = never come back. The app isn't brought back
     * while the screen is off: it waits, and comes back once the screen is on again.
     */
    fun next(nowMs: Long, hiddenSinceMs: Long, timeoutMs: Long, screenOn: Boolean): Action {
        if (timeoutMs <= 0) return Action.Never
        val left = hiddenSinceMs + timeoutMs - nowMs
        if (left > 0) return Action.Wait(left)
        if (!screenOn) return Action.Wait(SCREEN_OFF_RECHECK_MS)
        return Action.Return
    }
}
