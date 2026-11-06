package com.msp1974.vacompanion.odysseus.kiosk

// Odysseus fork: brings the app back to the front when another app (one opened from the
// dashboard, the app launcher, or Android's settings) has been in front for a while
// (KioskSettings.returnSeconds). Installed once from VACAApplication; watches MainActivity's
// start and stop. See docs/odysseus/kiosk.md.

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.msp1974.vacompanion.MainActivity
import timber.log.Timber

object ReturnToDashboard : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var app: Application? = null

    // When MainActivity left the front; null while it is in front or closed on purpose.
    private var hiddenSinceMs: Long? = null

    private val check = Runnable { runCheck() }

    fun install(application: Application) {
        if (app != null) return
        app = application
        KioskSettings.init(application)
        application.registerActivityLifecycleCallbacks(this)
    }

    private fun now() = SystemClock.elapsedRealtime()

    override fun onActivityStarted(activity: Activity) {
        if (activity !is MainActivity) return
        hiddenSinceMs = null
        handler.removeCallbacks(check)
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity !is MainActivity) return
        // Closing the app on purpose (Close app, a restart) or a rotation isn't "another app".
        if (activity.isFinishing || activity.isChangingConfigurations) return
        hiddenSinceMs = now()
        schedule(0)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is MainActivity) return
        hiddenSinceMs = null
        handler.removeCallbacks(check)
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(check)
        handler.postDelayed(check, delayMs)
    }

    private fun runCheck() {
        val context = app ?: return
        val since = hiddenSinceMs ?: return
        val power = context.getSystemService(PowerManager::class.java)
        val action = ReturnPolicy.next(
            nowMs = now(),
            hiddenSinceMs = since,
            timeoutMs = KioskSettings.returnSeconds * 1000L,
            screenOn = power?.isInteractive ?: true,
        )
        when (action) {
            is ReturnPolicy.Action.Wait -> schedule(action.ms)
            ReturnPolicy.Action.Never -> Unit
            ReturnPolicy.Action.Return -> bringToFront(context)
        }
    }

    private fun bringToFront(context: Application) {
        Timber.i("Kiosk: another app was in front for ${KioskSettings.returnSeconds} s, coming back")
        // Stop here: if Android blocks the start, don't retry in a loop. The next time the app
        // leaves the front starts a new timer.
        hiddenSinceMs = null
        try {
            // MainActivity is singleTask: this brings its task to the front and closes anything
            // opened on top of it in that task.
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w("Kiosk: couldn't come back to the front: ${e.message}")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
