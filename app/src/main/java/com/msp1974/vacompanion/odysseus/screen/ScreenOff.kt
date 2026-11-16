package com.msp1974.vacompanion.odysseus.screen

// Odysseus fork: the screensaver turns the panel off for real (DevicePolicyManager.lockNow, as
// upstream's "screen off" already does) when the app is a device admin, instead of black at 1%
// backlight all night. Rules: ScreenOffRules.kt. Hooks in MainActivity.applyScreenMode,
// onScreenSaver and the screen on/off broadcasts. See docs/odysseus/power.md.
//
// What keeps working with the panel off: the foreground service (microphone, Wyoming server,
// Sendspin) and a partial wake lock (ScreenUtils.setPartialWakeLock, as upstream's screen off).
// Everything that wakes the screen goes through applyScreenMode(ON) -> ScreenUtils.wakeScreen
// (FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP), which also wakes it from lockNow.

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.msp1974.vacompanion.VACADeviceAdminReceiver
import com.msp1974.vacompanion.device.ScreenOnMode
import com.msp1974.vacompanion.device.ScreenUtils
import com.msp1974.vacompanion.odysseus.kiosk.KioskSettings
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

object ScreenOff {
    private val state = ScreenOffState()

    /** The panel is off because of the screensaver. */
    val isOffForScreensaver: Boolean get() = state.offForScreensaver

    /**
     * Called for every screen mode MainActivity applies. Returns true when it handled [mode]
     * itself (the screensaver, panel turned off); false: apply [mode] as usual.
     */
    fun apply(
        context: Context,
        config: APPConfig,
        screen: ScreenUtils,
        mode: ScreenOnMode,
        startup: Boolean,
        setOverlay: (Boolean) -> Unit,
    ): Boolean {
        state.left()
        if (mode != ScreenOnMode.ON_DARK) return false
        KioskSettings.init(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context.applicationContext, VACADeviceAdminReceiver::class.java)
        val choice = ScreenOffPolicy.screensaver(
            canLock = dpm?.isAdminActive(admin) == true,
            startup = startup,
            touchEndsScreensaver = config.screenSaverDisableOnTouch,
            fullOffChosen = KioskSettings.screenFullOff,
            motionWakes = config.enableMotionDetection && config.screenOnMotion,
        )
        if (choice.look == ScreensaverLook.DIM_BLACK) {
            Timber.i("Screensaver: black at 1%% backlight (%s)", choice.reason)
            return false
        }
        // No black overlay: whatever wakes the panel next shows the screen it asked for.
        setOverlay(false)
        screen.setPartialWakeLock() // the wake word keeps listening; released by the next wakeScreen
        state.entered()
        return try {
            dpm!!.lockNow()
            Timber.i("Screensaver: panel off (%s)", choice.reason)
            true
        } catch (e: SecurityException) {
            Timber.w("Screensaver: lockNow refused (%s), black at 1%% backlight instead", e.message)
            state.lockFailed()
            false
        }
    }

    /** ACTION_SCREEN_OFF. True: the screensaver turned it off; don't report "screen off". */
    fun onSystemScreenOff(): Boolean = state.systemScreenOff()

    /** ACTION_SCREEN_ON. True: something else (a power button) woke the panel; end the screensaver. */
    fun onSystemScreenOn(): Boolean {
        val external = state.systemScreenOn()
        if (external) Timber.i("Screensaver: panel woken by the system, ending the screensaver")
        return external
    }

    /** Ending the screensaver: true turns the screen on, false off (upstream's rule). */
    fun screensaverEndTurnsOn(screenIsOn: Boolean): Boolean = state.screensaverEndTurnsOn(screenIsOn)
}
