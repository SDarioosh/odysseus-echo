package com.msp1974.vacompanion.odysseus.kiosk

// Odysseus fork: reloads the Home Assistant dashboard by itself when it failed to load (the
// error page, also shown for a server error while HA restarts), so a wall tablet never sits on
// an error. Composed inside WebViewScreen (hook in ui/layouts/WebViewLayout.kt), so it only runs
// while the dashboard is on screen. See docs/odysseus/kiosk.md.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.utils.CustomWebView
import kotlinx.coroutines.delay
import timber.log.Timber

/** The page CustomWebViewClient loads when the dashboard fails (its private ERROR_URL). */
const val DASHBOARD_ERROR_URL = "file:///android_asset/web/error.html"

// How often to look whether the error page is showing.
private const val POLL_MS = 2_000L

// Kept outside composition so the wait keeps growing across reloads and screen changes.
private val backoff = ReloadBackoff()

@Composable
fun DashboardAutoReload(webView: CustomWebView, stage: PageLoadingStage, networkUp: Boolean) {
    KioskSettings.init(LocalContext.current)
    val enabled = KioskSettings.autoReload
    val currentStage by rememberUpdatedState(stage)

    DisposableEffect(Unit) {
        backoff.onShown()
        onDispose { }
    }

    LaunchedEffect(stage == PageLoadingStage.LOADED, networkUp) {
        backoff.observe(loaded = stage == PageLoadingStage.LOADED, networkUp = networkUp)
    }

    // Look at what the WebView shows rather than only the page state: after a failed load,
    // sign-in can still mark the page state "authorised" while the error page is showing.
    LaunchedEffect(webView, enabled, networkUp) {
        fun failed() = webView.url == DASHBOARD_ERROR_URL || currentStage == PageLoadingStage.ERROR
        while (true) {
            delay(POLL_MS)
            if (!failed()) continue
            // Null: off or no network; this effect starts again when either changes.
            val wait = backoff.nextDelayMs(failed = true, networkUp = networkUp, enabled = enabled) ?: break
            Timber.i("Kiosk: dashboard failed to load, reloading in ${wait / 1000} s")
            delay(wait)
            if (failed()) webView.refresh()
        }
    }
}
