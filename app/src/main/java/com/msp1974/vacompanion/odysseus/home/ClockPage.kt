package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: page 0 of the home screen, where the idle timer returns to. What it shows and
// how it looks come from the clock style (home/clock/, Menu → Odysseus → Home screen → Clock
// style): a theme (colours, fonts, background) and elements (time, date, weather, now playing,
// Odysseus's Today data, status, record button) placed at nine anchors (top/middle/bottom × left/centre/
// right); elements sharing an anchor stack in order. The "Classic" style is the clock as it was before styles.
// Built for the Echo Show 5 (about 790×395 dp, 1 GB RAM): the clock redraws once a minute, not
// every second.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.odysseus.home.clock.ClockElement
import com.msp1974.vacompanion.odysseus.home.clock.ClockPresets
import com.msp1974.vacompanion.odysseus.home.clock.ClockStyle
import com.msp1974.vacompanion.odysseus.home.clock.NightMode
import com.msp1974.vacompanion.odysseus.home.clock.ClockBackground
import com.msp1974.vacompanion.odysseus.home.clock.ClockElementView
import com.msp1974.vacompanion.odysseus.home.clock.ClockFrame
import com.msp1974.vacompanion.odysseus.home.clock.ClockThemed
import com.msp1974.vacompanion.odysseus.home.clock.ElementType
import com.msp1974.vacompanion.odysseus.home.clock.Variants
import com.msp1974.vacompanion.odysseus.home.clock.nowPlayingVisible
import com.msp1974.vacompanion.odysseus.home.clock.rememberArtwork
import kotlinx.coroutines.delay

// Redraw just after the minute changes, so the clock never shows the old minute.
private const val MINUTE_MS = 60_000L
private const val TICK_MARGIN_MS = 50L

object ClockPage : HomePage {
    override val id: String = "clock"

    @Composable
    override fun Content(context: HomePageContext, modifier: Modifier) {
        // The night look (clock/NightMode) while the room is dark, if turned on.
        val night = HomeSettings.nightAuto && NightMode.active
        val style = if (night) {
            remember(HomeSettings.nightPreset) { ClockStyle.normalized(ClockPresets.byId(HomeSettings.nightPreset) ?: ClockPresets.nightstand) }
        } else {
            HomeSettings.clockStyle
        }
        if (night && HomeSettings.nightDim) NightMode.DimBacklight()
        val theme = style.theme
        val now = rememberMinuteClock()
        val is24h = DateFormat.is24HourFormat(LocalContext.current)
        val artwork = rememberArtwork(context.nowPlaying)
        val playing = nowPlayingVisible(context.nowPlaying)

        ClockThemed(theme) {
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                // Size the time from the space available, so it fills a small or a large screen.
                val baseTimeSize = with(LocalDensity.current) { minOf(maxHeight * 0.36f, maxWidth * 0.2f).toSp() }
                val usable = maxWidth - 48.dp // inside the page padding
                ClockBackground(theme, if (playing) artwork else null, if (playing) context.nowPlaying.colors.backgroundDark else 0)

                val frame = ClockFrame(context, theme, baseTimeSize, now, is24h, artwork)
                val shown = style.elements.filter { it.visible }
                // A now-playing bar runs along the bottom edge, outside the page's padding.
                val bar = shown.firstOrNull { it.type == ElementType.NOW_PLAYING && it.style == Variants.NP_BAR }
                val barShowing = bar != null && playing

                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(theme.dim)
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                        .padding(bottom = if (barShowing) 44.dp else 0.dp),
                ) {
                    // One full-height column per side (left, middle, right): its top elements, then
                    // its middle ones centred in the space left, then its bottom ones. Elements in a
                    // column never overlap; a column that is too full pushes its bottom ones off.
                    // A side with a set width (a panel) leaves the rest to the other columns, so
                    // a big time shrinks to fit beside it instead of running under it.
                    val setWidth = (0..2).associateWith { c -> shown.filter { it.anchor.col == c && it !== bar }.maxOfOrNull { it.width } ?: 0f }
                    for (col in 0..2) {
                        val inCol = shown.filter { it.anchor.col == col && it !== bar }
                        if (inCol.isEmpty()) continue
                        val others = (0..2).filter { it != col }.sumOf { setWidth.getValue(it).toDouble() }.toFloat()
                        val maxShare = (1f - others - if (others > 0f) 0.03f else 0f).coerceIn(0.3f, 1f)
                        val top = inCol.filter { it.anchor.row == 0 }
                        val middle = inCol.filter { it.anchor.row == 1 }
                        val bottom = inCol.filter { it.anchor.row == 2 }
                        val width = inCol.maxOf { it.width }
                        val fillsMiddle = middle.any { it.fillHeight }
                        Column(
                            modifier = Modifier
                                .align(when (col) { 0 -> Alignment.TopStart; 2 -> Alignment.TopEnd; else -> Alignment.TopCenter })
                                .fillMaxHeight()
                                .then(if (width > 0f) Modifier.fillMaxWidth(width) else Modifier.widthIn(max = usable * maxShare)),
                            horizontalAlignment = when (col) {
                                0 -> Alignment.Start
                                2 -> Alignment.End
                                else -> Alignment.CenterHorizontally
                            },
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            @Composable
                            fun draw(element: ClockElement, fill: Boolean) = key(element.type) {
                                ClockElementView(
                                    element, frame,
                                    Modifier
                                        .offset(element.dx.dp, element.dy.dp)
                                        .then(if (fill) Modifier.weight(1f, fill = true) else Modifier),
                                )
                            }
                            top.forEach { draw(it, false) }
                            if (fillsMiddle) {
                                middle.forEach { draw(it, it.fillHeight) }
                            } else {
                                Spacer(Modifier.weight(1f))
                                middle.forEach { draw(it, false) }
                                if (middle.isNotEmpty()) Spacer(Modifier.weight(1f))
                            }
                            bottom.forEach { draw(it, false) }
                        }
                    }
                }
                if (bar != null) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().alpha(theme.dim)) {
                        ClockElementView(bar, frame)
                    }
                }
            }
        }
    }
}

/** The current time, recomputed at each minute boundary and when the time or time zone changes. */
@Composable
private fun rememberMinuteClock(): Long {
    val context = LocalContext.current
    // Bumped by the time/time-zone receiver to force an immediate refresh.
    var clockChanges by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                clockChanges++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // Protected system broadcasts reach a not-exported receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val now by produceState(initialValue = System.currentTimeMillis(), clockChanges) {
        while (true) {
            value = System.currentTimeMillis()
            delay(MINUTE_MS - value % MINUTE_MS + TICK_MARGIN_MS)
        }
    }
    return now
}
