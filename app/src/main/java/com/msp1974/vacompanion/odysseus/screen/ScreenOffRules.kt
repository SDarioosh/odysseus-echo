package com.msp1974.vacompanion.odysseus.screen

// Odysseus fork: when the screensaver turns the panel off for real instead of drawing black at 1%
// backlight, and what the screen-on/off broadcasts mean while it does (plain Kotlin, tested in
// ScreenOffRulesTest; the Android side is ScreenOff.kt). See docs/odysseus/power.md.

/** How the screensaver looks: the panel really off, or today's black screen at 1% backlight. */
enum class ScreensaverLook { PANEL_OFF, DIM_BLACK }

data class ScreensaverChoice(val look: ScreensaverLook, val reason: String)

object ScreenOffPolicy {
    /**
     * [canLock]: the app is an active device admin (or owner) with `force-lock`.
     * [startup]: the app started with the screen off and keeps it on, black, while the dashboard
     * loads; that is not the screensaver. [touchEndsScreensaver]: Home Assistant's "screen saver
     * disable on touch"; a touch can't reach a panel that is off, so the panel goes off only
     * when touch doesn't end the screensaver anyway or the owner chose [fullOffChosen].
     * [motionWakes]: camera motion detection turns the screen on; the camera may stop while the
     * panel is off (upstream restarts it on every screen-on), so the same choice applies.
     */
    fun screensaver(
        canLock: Boolean,
        startup: Boolean,
        touchEndsScreensaver: Boolean,
        fullOffChosen: Boolean,
        motionWakes: Boolean = false,
    ): ScreensaverChoice = when {
        startup -> ScreensaverChoice(ScreensaverLook.DIM_BLACK, "start-up, the dashboard is loading")
        !canLock -> ScreensaverChoice(ScreensaverLook.DIM_BLACK, "not a device admin")
        touchEndsScreensaver && !fullOffChosen ->
            ScreensaverChoice(ScreensaverLook.DIM_BLACK, "a touch ends the screensaver and needs a lit panel")
        motionWakes && !fullOffChosen ->
            ScreensaverChoice(ScreensaverLook.DIM_BLACK, "camera motion wakes the screen and may stop with the panel off")
        else -> ScreensaverChoice(ScreensaverLook.PANEL_OFF, "device admin")
    }
}

/**
 * Whether the panel is off because of the screensaver. Not thread-safe: used on the main thread.
 *
 * - [entered] just before the app locks the screen; [left] when any other screen mode is applied
 *   (wake word, Home Assistant, announcement, …), which then turns the panel back on.
 * - While the app's own lock is pending, the system's screen-off is not reported to Home
 *   Assistant as "screen off": as with the dim screensaver, it still sees screen on + screensaver.
 * - A screen-on the app didn't cause (a power button) ends the screensaver, as a touch would.
 */
class ScreenOffState {
    /** The panel is off because of the screensaver, and nothing has turned it on since. */
    var offForScreensaver = false
        private set

    /** The app locked the screen and the system hasn't reported the screen on since. */
    private var lockedByApp = false

    fun entered() {
        offForScreensaver = true
        lockedByApp = true
    }

    /** Any screen mode applied by the app. */
    fun left() {
        offForScreensaver = false
    }

    /** The lock was refused: the panel stays on, nothing to hide from Home Assistant. */
    fun lockFailed() {
        offForScreensaver = false
        lockedByApp = false
    }

    /** The system says the screen went off. True: don't report it (the app's own screensaver). */
    fun systemScreenOff(): Boolean = lockedByApp

    /** The system says the screen came on. True: something else woke it; end the screensaver. */
    fun systemScreenOn(): Boolean {
        lockedByApp = false
        val external = offForScreensaver
        offForScreensaver = false
        return external
    }

    /** Ending the screensaver turns the screen on (not off) when the panel is off because of it. */
    fun screensaverEndTurnsOn(screenIsOn: Boolean): Boolean = screenIsOn || offForScreensaver
}
