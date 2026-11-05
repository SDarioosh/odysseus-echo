package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: the one place that holds where the home screen is. A process-wide object so the
// state survives the screen going blank and coming back (HomeHost leaves composition then), and
// so upstream hooks (WebViewLayout's gesture listener) reach it without new wiring. Compose
// reads `destination`; everything else calls the methods below.

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.utils.WebViewGestureDetector
import timber.log.Timber

object HomeScreenController {
    private val navigator = HomeNavigator(
        pageCount = { HomePages.all.size },
        enabled = { HomeSettings.enabled },
        idleTimeoutMs = { HomeSettings.idleSeconds * 1000L },
    )

    // Bumped on every navigator change so Compose re-reads `destination`.
    private var version by mutableStateOf(0)

    private fun now() = SystemClock.elapsedRealtime()

    /** What is shown now. Snapshot state: reading it in a composable recomposes on change. */
    val destination: HomeDestination
        get() {
            version // read for Compose
            // Settings are snapshot state too, so a settings change also recomposes.
            HomeSettings.enabled; HomeSettings.idleSeconds
            return navigator.current()
        }

    /** When the idle timer fires (elapsedRealtime), or null. Read like [destination]. */
    val idleDeadlineMs: Long?
        get() {
            version; HomeSettings.enabled; HomeSettings.idleSeconds
            return navigator.idleDeadlineMs()
        }

    fun onUserActivity() {
        navigator.onUserActivity(now())
        version++ // the idle deadline moved
    }

    /** A horizontal swipe on a native page or the dashboard. True if consumed. */
    fun onSwipe(direction: SwipeDirection): Boolean {
        val moved = navigator.onSwipe(direction, now())
        if (moved) Timber.d("Home screen: swipe $direction -> ${navigator.current()}")
        version++
        return moved
    }

    /**
     * Hook for the dashboard's gesture listener (ui/layouts/WebViewLayout.kt). Only a plain
     * one-finger left/right swipe can move the home screen; returns true if it did, and the
     * caller then doesn't forward the gesture to Home Assistant.
     */
    fun onDashboardGesture(event: WebViewGestureDetector.GestureEvent): Boolean {
        if (event.pointers != 1) return false
        val direction = when (event.direction) {
            WebViewGestureDetector.Direction.LEFT -> SwipeDirection.LEFT
            WebViewGestureDetector.Direction.RIGHT -> SwipeDirection.RIGHT
            else -> return false
        }
        return onSwipe(direction)
    }

    fun goTo(target: HomeDestination) {
        navigator.goTo(target, now())
        version++
    }

    /** Called by HomeHost's idle timer. */
    fun checkIdle() {
        if (navigator.checkIdle(now())) {
            Timber.d("Home screen: idle, back to page 0")
            version++
        }
    }
}
