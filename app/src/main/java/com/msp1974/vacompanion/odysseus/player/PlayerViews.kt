package com.msp1974.vacompanion.odysseus.player

// Odysseus fork: the player pieces the clock draws (moved here from home/clock/ClockElements.kt
// without changing how they look), public and over the neutral PlayerState, so any page can show
// any room's player. Colours come from MaterialTheme; controls go out as onControl(Control, value).

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.PlayerState
import kotlinx.coroutines.delay

/** A player control and its value (seek seconds, volume 0..1, …; null for most). */
typealias OnControl = (Control, Any?) -> Unit

/** The clock of [PlayerState.positionAtMs] for Sendspin; a backend on another clock passes its own. */
val elapsedRealtimeClock: () -> Long = { SystemClock.elapsedRealtime() }

private fun TextUnit.times(f: Float): TextUnit = (value * f).sp

private fun PlayerState.titleOrUnknown(): String = title?.takeIf { it.isNotBlank() } ?: "Unknown track"

/**
 * The artist, then "Paused" when [showPaused]. [showPaused] defaults to "not playing"; the clock
 * passes Sendspin's "stream stopped" instead, as it always did.
 */
fun playerSubtitle(state: PlayerState, showPaused: Boolean = !state.playing): String = listOfNotNull(
    state.artist?.takeIf { it.isNotBlank() }, "Paused".takeIf { showPaused },
).joinToString(" · ")

/** The cover art, or a music note on the secondary container while there's none. */
@Composable
fun Artwork(bitmap: Bitmap?, size: Dp, shape: Shape) {
    Box(
        modifier = Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = "Album artwork",
                contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Previous, play/pause (only when the player takes it), next; in the caller's row. */
@Composable
fun Transport(
    state: PlayerState,
    onControl: OnControl,
    size: Dp = 24.dp,
    skips: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onBackground,
) {
    val paused = !state.playing
    if (skips) SkipButton(Icons.Rounded.SkipPrevious, "Previous track", Control.PREVIOUS in state.can, size, tint) { onControl(Control.PREVIOUS, null) }
    val control = if (paused) Control.PLAY else Control.PAUSE
    if (control in state.can) {
        IconButton(onClick = { onControl(control, null) }) {
            Icon(
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = if (paused) "Play" else "Pause", tint = tint, modifier = Modifier.size(size),
            )
        }
    }
    if (skips) SkipButton(Icons.Rounded.SkipNext, "Next track", Control.NEXT in state.can, size, tint) { onControl(Control.NEXT, null) }
}

@Composable
fun SkipButton(icon: ImageVector, description: String, enabled: Boolean, size: Dp, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = description, tint = tint.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(size))
    }
}

@Composable
fun TitleArtist(
    state: PlayerState,
    titleSize: TextUnit,
    artistSize: TextUnit,
    modifier: Modifier = Modifier,
    showPaused: Boolean = !state.playing,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            state.titleOrUnknown(), fontSize = titleSize, fontWeight = FontWeight.Medium,
            color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val sub = playerSubtitle(state, showPaused)
        if (sub.isNotEmpty()) {
            Text(sub, fontSize = artistSize, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The original clock's row: artwork, title and artist, previous / play-pause / next. */
@Composable
fun RowPlayer(state: PlayerState, art: Bitmap?, onControl: OnControl, showPaused: Boolean = !state.playing) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 16.dp).widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Artwork(art, 56.dp, RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.titleOrUnknown(), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = playerSubtitle(state, showPaused)
            if (sub.isNotEmpty()) {
                Text(
                    sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Transport(state, onControl)
    }
}

/** A rounded pill: round artwork, title and artist, play/pause. [s] scales it. */
@Composable
fun PillPlayer(state: PlayerState, art: Bitmap?, onControl: OnControl, s: Float = 1f, showPaused: Boolean = !state.playing) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = (300 * s).dp)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.09f), RoundedCornerShape(50))
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Artwork(art, (42 * s).dp, CircleShape)
        Spacer(Modifier.width(12.dp))
        TitleArtist(state, 16.sp.times(s), 13.sp.times(s), Modifier.weight(1f, fill = false), showPaused)
        Transport(state, onControl, (24 * s).dp, skips = false)
    }
}

/** Artwork, title and artist, previous / play-pause / next, compact. [s] scales it. */
@Composable
fun MiniPlayer(state: PlayerState, art: Bitmap?, onControl: OnControl, s: Float = 1f, showPaused: Boolean = !state.playing) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = (420 * s).dp)) {
        Artwork(art, (48 * s).dp, RoundedCornerShape(10.dp))
        Spacer(Modifier.width(12.dp))
        TitleArtist(state, 15.sp.times(s), 14.sp.times(s), Modifier.weight(1f, fill = false), showPaused)
        Spacer(Modifier.width(4.dp))
        Transport(state, onControl, (22 * s).dp)
    }
}

/** One line of text: ♪ title — artist, play/pause in the accent colour. [s] scales it. */
@Composable
fun LinePlayer(state: PlayerState, onControl: OnControl, s: Float = 1f, showPaused: Boolean = !state.playing) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = (520 * s).dp)) {
        Text("♪ ", fontSize = 16.sp.times(s), color = colors.primary)
        Text(
            state.titleOrUnknown(), fontSize = 16.sp.times(s), fontWeight = FontWeight.Bold,
            color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        val sub = playerSubtitle(state, showPaused)
        if (sub.isNotEmpty()) {
            Text(" — $sub", fontSize = 16.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Transport(state, onControl, (22 * s).dp, skips = false, tint = colors.primary)
    }
}

/**
 * A 44 dp status bar the full width: "♪ PLAYING"/"♪ PAUSED", artist — title, the transport, and
 * [trailing] at the far end (the clock's open counts).
 */
@Composable
fun BarPlayer(
    state: PlayerState,
    onControl: OnControl,
    showPaused: Boolean = !state.playing,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(44.dp).background(colors.surfaceVariant),
    ) {
        Box(Modifier.fillMaxHeight().background(colors.primary).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(if (!showPaused) "♪ PLAYING" else "♪ PAUSED", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.background, maxLines = 1)
        }
        Text(
            listOf(state.artist.orEmpty(), state.titleOrUnknown()).filter { it.isNotBlank() }.joinToString(" — "),
            fontSize = 15.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp).weight(1f, fill = false),
        )
        Transport(state, onControl, 20.dp)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Big artwork, "NOW PLAYING", title, artist · album, progress, and large controls. */
@Composable
fun FullPlayer(
    state: PlayerState,
    art: Bitmap?,
    onControl: OnControl,
    s: Float = 1f,
    showPaused: Boolean = !state.playing,
    clock: () -> Long = elapsedRealtimeClock,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Artwork(art, (130 * s).dp, RoundedCornerShape(14.dp))
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text("NOW PLAYING", fontSize = 13.sp, letterSpacing = 3.sp, color = colors.onSurfaceVariant, maxLines = 1)
            Text(
                state.titleOrUnknown(), fontSize = 30.sp.times(s), fontWeight = FontWeight.SemiBold,
                color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                state.artist?.takeIf { it.isNotBlank() }, state.album?.takeIf { it.isNotBlank() }, "Paused".takeIf { showPaused },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 18.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if ((state.durationS ?: 0.0) > 0.0) Progress(state, clock)
        }
        Spacer(Modifier.width(12.dp))
        val paused = !state.playing
        SkipButton(Icons.Rounded.SkipPrevious, "Previous track", Control.PREVIOUS in state.can, 34.dp, colors.onBackground) { onControl(Control.PREVIOUS, null) }
        val control = if (paused) Control.PLAY else Control.PAUSE
        if (control in state.can) {
            Box(
                modifier = Modifier.size(60.dp).background(colors.onBackground, CircleShape).clip(CircleShape).clickable { onControl(control, null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (paused) "Play" else "Pause", tint = colors.background, modifier = Modifier.size(34.dp),
                )
            }
        }
        SkipButton(Icons.Rounded.SkipNext, "Next track", Control.NEXT in state.can, 34.dp, colors.onBackground) { onControl(Control.NEXT, null) }
    }
}

/** The song's progress as a thin bar, redrawn every second while it plays. */
@Composable
fun Progress(state: PlayerState, clock: () -> Long = elapsedRealtimeClock) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(state.playing, state.positionAtMs) {
        while (state.playing) {
            now = clock()
            delay(1_000)
        }
        now = clock()
    }
    val duration = state.durationS ?: 0.0
    val f = if (duration > 0.0) ((state.positionNow(now) ?: 0.0) / duration).toFloat().coerceIn(0f, 1f) else 0f
    val colors = MaterialTheme.colorScheme
    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).background(colors.onBackground.copy(alpha = 0.2f), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(f).height(4.dp).background(colors.onBackground, RoundedCornerShape(2.dp)))
    }
}
