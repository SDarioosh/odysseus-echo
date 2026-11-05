package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: the home screen host. MainActivity wraps the dashboard in it.
//
// Layering, bottom to top:
//   1. The Home Assistant dashboard (WebViewScreen). It is ALWAYS composed, also while the clock
//      covers it: WebViewScreen pauses the WebView when it leaves composition, which would stall
//      the HA frontend (navigation from HA, websocket, View Assist) while the clock shows. Its
//      quick-actions sheet and status icons stay exactly as upstream has them.
//   2. The native pages (HomePages.all), as one opaque layer on a horizontal strip:
//      dashboard = position -1, page i = position i. `offset` (in page widths) is where the strip
//      is; only pages within one width of it are composed, and when the dashboard is showing and
//      the animation has settled, nothing of this layer is composed at all.
//
// Gestures on the pages: a one-finger horizontal drag follows the finger and, on release, is
// handed to HomeScreenController.onSwipe (which decides where to go); a two-finger swipe up opens
// the settings menu, because the dashboard's own two-finger quick-actions gesture can't be reached
// while a page covers it. On the dashboard the WebView's gesture detector reports swipes
// (hook in ui/layouts/WebViewLayout.kt). Every touch anywhere restarts the idle timer, which
// sends the screen back to page 0 (the clock).

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msp1974.vacompanion.odysseus.home.weather.WeatherRepository
import com.msp1974.vacompanion.ui.VAViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** A drag further than this share of the width moves a page, whatever its speed. */
private const val DRAG_DISTANCE_FRACTION = 0.25f

/** A flick faster than this moves a page, however short. */
private val FLICK_VELOCITY = 800.dp

/** A two-finger swipe up longer than this opens the menu. */
private val MENU_SWIPE_DISTANCE = 100.dp

private fun HomeDestination.position(): Float = when (this) {
    HomeDestination.Dashboard -> -1f
    is HomeDestination.Page -> index.toFloat()
}

@Composable
fun HomeHost(dashboard: @Composable () -> Unit) {
    HomeSettings.init(LocalContext.current)
    val vaViewModel: VAViewModel = viewModel()
    val vaUiState by vaViewModel.vacaState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Watch every press (dashboard included, as an ancestor of its AndroidView) without
            // consuming it, to restart the idle timer.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDown() }) {
                            HomeScreenController.onUserActivity()
                        }
                    }
                }
            }
    ) {
        // Same slot whether or not the home screen is on, so toggling it never recreates the
        // dashboard.
        dashboard()

        // Weather for the clock page: at once, then every 15 min, while the home screen is on;
        // again when Home Assistant reconnects or the weather setting changes.
        val weatherOn = HomeSettings.enabled && HomeSettings.weatherEnabled
        val weatherEntity = HomeSettings.weatherEntity
        LaunchedEffect(weatherOn, weatherEntity, vaUiState.isServerConnected, vaUiState.isNetworkConnected) {
            if (!weatherOn) {
                WeatherRepository.clear()
                return@LaunchedEffect
            }
            if (!vaUiState.isNetworkConnected) return@LaunchedEffect
            WeatherRepository.refresh(vaViewModel.deviceManager, minAgeMs = 2 * 60_000L)
            while (true) {
                delay(WeatherRepository.REFRESH_MS)
                WeatherRepository.refresh(vaViewModel.deviceManager)
            }
        }

        if (HomeSettings.enabled && HomePages.all.isNotEmpty()) {
            val context = HomePageContext(
                nowPlaying = vaUiState.sendspinNowPlaying,
                isNetworkConnected = vaUiState.isNetworkConnected,
                isServerConnected = vaUiState.isServerConnected,
                isDND = vaUiState.isDND,
                weather = if (weatherOn) WeatherRepository.weather else null,
                onSendspinCommand = vaViewModel::onSendspinCommand,
            )
            PageLayer(context, onOpenMenu = { vaViewModel.onOpenSettingsAction() })
        }
    }
}

@Composable
private fun PageLayer(context: HomePageContext, onOpenMenu: () -> Unit) {
    val pages = HomePages.all
    val maxPosition = (pages.size - 1).toFloat()
    val destination = HomeScreenController.destination
    val offset = remember { Animatable(destination.position()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(destination) {
        offset.animateTo(destination.position(), spring(stiffness = Spring.StiffnessMediumLow))
    }

    val idleDeadline = HomeScreenController.idleDeadlineMs
    LaunchedEffect(idleDeadline) {
        val deadline = idleDeadline ?: return@LaunchedEffect
        delay(max(0L, deadline - SystemClock.elapsedRealtime()))
        HomeScreenController.checkIdle()
    }

    // Nothing at all while the dashboard shows and the strip has settled there.
    val layerVisible by remember { derivedStateOf { offset.value > -0.999f } }
    if (!layerVisible && destination == HomeDestination.Dashboard) return

    // The pages within one width of the strip's position: at most two at a time.
    val firstVisible by remember { derivedStateOf { floor(offset.value).toInt() } }

    val touchSlop = LocalViewConfiguration.current.touchSlop
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(widthPx, maxPosition) {
                    val flickPx = FLICK_VELOCITY.toPx()
                    val menuSwipePx = MENU_SWIPE_DISTANCE.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val startOffset = offset.value
                        var maxPointers = 1
                        var dragging = false
                        var dx = 0f
                        var dy = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            maxPointers = max(maxPointers, event.changes.count { it.pressed })
                            val primary = event.changes.firstOrNull { it.id == down.id }
                            if (primary == null || !primary.pressed) break
                            dx += primary.positionChange().x
                            dy += primary.positionChange().y
                            tracker.addPosition(primary.uptimeMillis, primary.position)

                            if (maxPointers >= 2) {
                                // A second finger turns this into the menu gesture: drop any drag.
                                if (dragging) {
                                    dragging = false
                                    scope.launch { offset.animateTo(HomeScreenController.destination.position()) }
                                }
                                continue
                            }
                            if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) dragging = true
                            if (dragging) {
                                primary.consume()
                                val target = (startOffset - dx / widthPx).coerceIn(-1f, maxPosition)
                                scope.launch { offset.snapTo(target) }
                            }
                        }

                        if (maxPointers >= 2) {
                            if (dy < -menuSwipePx && abs(dy) > abs(dx)) onOpenMenu()
                        } else if (dragging) {
                            val vx = tracker.calculateVelocity().x
                            val far = abs(dx) > widthPx * DRAG_DISTANCE_FRACTION
                            val flick = abs(vx) > flickPx && (vx > 0) == (dx > 0)
                            val moved = (far || flick) && HomeScreenController.onSwipe(
                                if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                            )
                            // When it moved, LaunchedEffect(destination) animates to the new page.
                            if (!moved) {
                                scope.launch { offset.animateTo(HomeScreenController.destination.position()) }
                            }
                        }
                    }
                }
        ) {
            for (index in listOf(firstVisible, firstVisible + 1)) {
                val page = pages.getOrNull(index) ?: continue
                // Keyed by index, so a page keeps its state as it moves between the two slots.
                key(index) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = (index - offset.value) * widthPx }
                            // Opaque, so the dashboard never shows through a page.
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        page.Content(context, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
