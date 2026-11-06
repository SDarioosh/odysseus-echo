package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: page 0 of the home screen, where the idle timer returns to. The weather in the
// top left (weather/WeatherBadge), a large clock with the date under it, then the song Sendspin
// is playing (artwork, title, artist, previous / play-pause / next), and a quiet status line
// only when something is wrong (no Wi-Fi, Home Assistant not connected, do not disturb).
// Built for the Echo Show 5 (about 790×395 dp, 1 GB RAM): the clock redraws once a minute, not
// every second.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.odysseus.home.weather.WeatherBadge
import com.msp1974.vacompanion.sendspin.SendspinArtwork
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A pause normally ends the Sendspin stream, so the track lingers a while to let it be resumed
// (same as ui/components/NowPlayingOverlay.kt).
private const val PAUSED_LINGER_MS = 20_000L

// Redraw just after the minute changes, so the clock never shows the old minute.
private const val MINUTE_MS = 60_000L
private const val TICK_MARGIN_MS = 50L

private val NOW_PLAYING_MAX_WIDTH = 480.dp
private val ARTWORK_SIZE = 56.dp

object ClockPage : HomePage {
    override val id: String = "clock"

    @Composable
    override fun Content(context: HomePageContext, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background),
            contentAlignment = Alignment.Center,
        ) {
            // Size the time from the space available, so it fills a small or a large screen.
            val timeSize = with(LocalDensity.current) {
                minOf(maxHeight * 0.36f, maxWidth * 0.2f).toSp()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Clock(timeSize)
                NowPlayingRow(context.nowPlaying, context.onSendspinCommand)
            }
            context.weather?.let {
                WeatherBadge(it, Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 16.dp))
            }
            StatusLine(context, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
            // A downloaded update from Odysseus (odysseus/update/UpdateBanner.kt); nothing otherwise.
            com.msp1974.vacompanion.odysseus.update.UpdateBanner(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp))
        }
    }
}

/** Time and date, recomputed at each minute boundary and when the time or time zone changes. */
@Composable
private fun Clock(timeSize: TextUnit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

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

    // Formats are rebuilt with each tick so a change of locale, 24-hour setting or time zone
    // shows within a minute (or at once for time/zone changes).
    val is24h = DateFormat.is24HourFormat(context)
    val locale = Locale.getDefault()
    val date = Date(now)
    val time = SimpleDateFormat(if (is24h) "H:mm" else "h:mm", locale).format(date)
    val amPm = if (is24h) null else SimpleDateFormat("a", locale).format(date)
    val day = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(date)

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = time,
            fontSize = timeSize,
            lineHeight = timeSize,
            fontWeight = FontWeight.Light,
            color = colors.onBackground,
            maxLines = 1,
        )
        if (amPm != null) {
            Text(
                text = amPm,
                fontSize = timeSize * 0.22f,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
        }
    }
    Text(
        text = day,
        style = MaterialTheme.typography.titleLarge,
        color = colors.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * The current Sendspin track: shown while playing, kept dimmed as "Paused" for a while after the
 * stream stops, then animated away.
 */
@Composable
private fun NowPlayingRow(nowPlaying: SendspinNowPlaying, onCommand: (String) -> Unit) {
    var visible by remember { mutableStateOf(nowPlaying.active && nowPlaying.hasContent) }
    LaunchedEffect(nowPlaying.active, nowPlaying.hasContent) {
        if (nowPlaying.active && nowPlaying.hasContent) {
            visible = true
        } else {
            delay(PAUSED_LINGER_MS)
            visible = false
        }
    }

    // Keep the last track on screen while it lingers or animates away, even after the server
    // has dropped its metadata.
    var shown by remember { mutableStateOf(nowPlaying) }
    shown = if (nowPlaying.hasContent) nowPlaying else shown.copy(active = false, playing = false)

    AnimatedVisibility(
        visible = visible && shown.hasContent,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        TrackRow(shown, onCommand)
    }
}

@Composable
private fun TrackRow(nowPlaying: SendspinNowPlaying, onCommand: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val paused = !nowPlaying.active || !nowPlaying.playing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 20.dp)
            .widthIn(max = NOW_PLAYING_MAX_WIDTH)
            .fillMaxWidth()
            .alpha(if (nowPlaying.active) 1f else 0.6f),
    ) {
        Artwork(nowPlaying.artwork, nowPlaying.artworkUrl)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nowPlaying.title.ifBlank { "Unknown track" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(
                nowPlaying.artist.takeIf { it.isNotBlank() },
                "Paused".takeIf { !nowPlaying.active },
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Previous / play-pause / next. Skip buttons are always there (dimmed when Music
        // Assistant doesn't offer them), as on the dashboard's now-playing card, so the row
        // doesn't jump around between tracks.
        SkipButton(Icons.Rounded.SkipPrevious, "Previous track", "previous" in nowPlaying.commands) {
            onCommand("previous")
        }
        val command = if (paused) "play" else "pause"
        if (command in nowPlaying.commands) {
            IconButton(onClick = { onCommand(command) }) {
                Icon(
                    imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (paused) "Play" else "Pause",
                    tint = colors.onBackground,
                )
            }
        }
        SkipButton(Icons.Rounded.SkipNext, "Next track", "next" in nowPlaying.commands) {
            onCommand("next")
        }
    }
}

@Composable
private fun SkipButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

@Composable
private fun Artwork(pushed: Bitmap?, url: String?) {
    // Art pushed over the Sendspin connection first; the URL only when there is none (it points
    // at the server's LAN address, out of reach over Tailscale).
    val fetched by produceState<Bitmap?>(initialValue = null, url, pushed) {
        value = if (pushed == null) url?.let { SendspinArtwork.load(it) } else null
    }
    val bitmap = pushed ?: fetched
    Box(
        modifier = Modifier
            .size(ARTWORK_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val art = bitmap
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(ARTWORK_SIZE * 0.5f),
            )
        }
    }
}

/** Shown only when something needs attention; empty otherwise. */
@Composable
private fun StatusLine(context: HomePageContext, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val problem = when {
        !context.isNetworkConnected -> "Wi-Fi disconnected"
        !context.isServerConnected -> "Home Assistant disconnected"
        else -> null
    }
    if (problem == null && !context.isDND) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.labelLarge, color = colors.error)
        }
        if (context.isDND) {
            Text("Do not disturb", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
    }
}
