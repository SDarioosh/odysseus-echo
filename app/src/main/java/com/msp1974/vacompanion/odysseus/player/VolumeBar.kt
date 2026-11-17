package com.msp1974.vacompanion.odysseus.player

// Odysseus fork: a volume control without horizontal dragging (the home screen owns horizontal
// swipes): mute, minus, the level as a bar, plus. Sends VOLUME (Float 0..1) and MUTE (Boolean).
// The last volume sent is shown (and stepped from) until the player reports it back, so quick taps
// add up instead of each stepping from the old reported level.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.PlayerState
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** The volume steps of [VolumeBar], plain Kotlin. */
object VolumeSteps {
    /** One [step] up or down from [volume], snapped to the step and kept in 0..1. */
    fun next(volume: Float, up: Boolean, step: Float = 0.05f): Float {
        val steps = (volume / step).roundToInt() + if (up) 1 else -1
        return (steps * step).coerceIn(0f, 1f)
    }

    /** How long a sent volume overrides the reported one while waiting for the player's echo. */
    const val PENDING_MS = 2_000L

    /** Reported and sent volumes closer than this are the same (players round). */
    private const val SAME = 0.01f

    /** True when [reported] is [pending] coming back from the player. */
    fun echoed(reported: Float, pending: Float): Boolean = abs(reported - pending) < SAME

    /**
     * The volume to show and step from: [pending] (the last one sent, at [sentAtMs]) until the
     * player reports it back or [timeoutMs] has passed since; else [reported].
     */
    fun current(reported: Float, pending: Float?, sentAtMs: Long, nowMs: Long, timeoutMs: Long = PENDING_MS): Float =
        if (pending != null && nowMs - sentAtMs < timeoutMs && !echoed(reported, pending)) pending else reported
}

/**
 * Mute toggle, minus, the level, plus. Shown only while the player reports a volume; the buttons
 * are enabled when it takes [Control.VOLUME] / [Control.MUTE].
 */
@Composable
fun VolumeBar(
    state: PlayerState,
    onControl: OnControl,
    modifier: Modifier = Modifier,
    step: Float = 0.05f,
    iconSize: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onBackground,
) {
    val reported = state.volume ?: return
    // The last volume sent, until the player reports it back (VolumeSteps.current).
    var pending by remember { mutableStateOf<Float?>(null) }
    var sentAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pending, sentAt) {
        val p = pending ?: return@LaunchedEffect
        delay(VolumeSteps.PENDING_MS)
        if (pending == p) pending = null
    }
    LaunchedEffect(reported) {
        pending?.let { if (VolumeSteps.echoed(reported, it)) pending = null }
    }
    val volume = VolumeSteps.current(reported, pending, sentAt, SystemClock.elapsedRealtime())
    fun send(up: Boolean) {
        val next = VolumeSteps.next(volume, up, step)
        pending = next
        sentAt = SystemClock.elapsedRealtime()
        onControl(Control.VOLUME, next)
    }
    val canVolume = Control.VOLUME in state.can
    val canMute = Control.MUTE in state.can
    fun faded(enabled: Boolean) = tint.copy(alpha = if (enabled) 1f else 0.38f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onControl(Control.MUTE, !state.muted) }, enabled = canMute) {
            Icon(
                if (state.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (state.muted) "Unmute" else "Mute", tint = faded(canMute), modifier = Modifier.size(iconSize),
            )
        }
        IconButton(onClick = { send(up = false) }, enabled = canVolume && volume > 0f) {
            Icon(Icons.Rounded.Remove, contentDescription = "Volume down", tint = faded(canVolume && volume > 0f), modifier = Modifier.size(iconSize))
        }
        Box(
            Modifier.weight(1f).height(6.dp).background(tint.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
        ) {
            val level = if (state.muted) 0f else volume.coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(level).height(6.dp).background(faded(canVolume), RoundedCornerShape(3.dp)))
        }
        IconButton(onClick = { send(up = true) }, enabled = canVolume && volume < 1f) {
            Icon(Icons.Rounded.Add, contentDescription = "Volume up", tint = faded(canVolume && volume < 1f), modifier = Modifier.size(iconSize))
        }
    }
}
