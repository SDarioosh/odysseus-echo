package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: Menu → Odysseus → Clock face: the clock as it will look, drawn at the screen's
// real size and scaled down, so every change in the editor shows at once without closing the
// menu. Touches go to the menu, not the clock (no record button or transport presses). Weather and
// Today come from the same places the clock reads; music is a sample track when asked for, so a
// layout's now-playing element can be placed while nothing plays.

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.odysseus.home.ClockPage
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.home.today.TodayRepository
import com.msp1974.vacompanion.odysseus.home.weather.WeatherNow
import com.msp1974.vacompanion.odysseus.home.weather.WeatherRepository
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying

private val SAMPLE_WEATHER = WeatherNow("preview", "partlycloudy", 21.0, "°C", false, high = 24.0, low = 15.0)

private val SAMPLE_MUSIC = SendspinNowPlaying(
    title = "Song title", artist = "Artist", album = "Album",
    active = true, playing = true, commands = setOf("play", "pause", "next", "previous"),
)

/** The clock in [style], scaled to the width it is given (the screen's own shape). */
@Composable
fun ClockLivePreview(style: ClockStyle, withMusic: Boolean, modifier: Modifier = Modifier) {
    val config = LocalConfiguration.current
    // The clock fills the whole screen, so it is laid out at the screen's size and scaled.
    val screenW = config.screenWidthDp.coerceAtLeast(320).toFloat()
    val screenH = config.screenHeightDp.coerceAtLeast(200).toFloat()
    val weather = if (HomeSettings.weatherEnabled) WeatherRepository.weather ?: SAMPLE_WEATHER else null
    val today = TodayRepository.view
    val context = remember(weather, today, withMusic) {
        HomePageContext(
            nowPlaying = if (withMusic) SAMPLE_MUSIC else SendspinNowPlaying(),
            weather = weather,
            today = today,
        )
    }
    // "Now playing from: Selected room" reads the real room; the sample needs this device.
    val shown = if (withMusic) style.copy(nowPlayingFrom = NowPlayingSource.DEVICE) else style
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(screenW / screenH)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics { contentDescription = "Clock preview" },
    ) {
        val scale = maxWidth.value / screenW
        Box(
            Modifier
                .requiredSize(screenW.dp, screenH.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        ) {
            ClockPage.ClockStyleContent(shown, context)
        }
        // The preview is a picture: its buttons don't work.
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            },
        )
    }
}
