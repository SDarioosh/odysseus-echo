package com.msp1974.vacompanion.odysseus.player

// Odysseus fork: a volume control without horizontal dragging (the home screen owns horizontal
// swipes): mute, minus, the level as a bar, plus. Sends VOLUME (Float 0..1) and MUTE (Boolean).

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.PlayerState
import kotlin.math.roundToInt

/** The volume steps of [VolumeBar], plain Kotlin. */
object VolumeSteps {
    /** One [step] up or down from [volume], snapped to the step and kept in 0..1. */
    fun next(volume: Float, up: Boolean, step: Float = 0.05f): Float {
        val steps = (volume / step).roundToInt() + if (up) 1 else -1
        return (steps * step).coerceIn(0f, 1f)
    }
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
    val volume = state.volume ?: return
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
        IconButton(onClick = { onControl(Control.VOLUME, VolumeSteps.next(volume, up = false, step)) }, enabled = canVolume && volume > 0f) {
            Icon(Icons.Rounded.Remove, contentDescription = "Volume down", tint = faded(canVolume && volume > 0f), modifier = Modifier.size(iconSize))
        }
        Box(
            Modifier.weight(1f).height(6.dp).background(tint.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
        ) {
            val level = if (state.muted) 0f else volume.coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(level).height(6.dp).background(faded(canVolume), RoundedCornerShape(3.dp)))
        }
        IconButton(onClick = { onControl(Control.VOLUME, VolumeSteps.next(volume, up = true, step)) }, enabled = canVolume && volume < 1f) {
            Icon(Icons.Rounded.Add, contentDescription = "Volume up", tint = faded(canVolume && volume < 1f), modifier = Modifier.size(iconSize))
        }
    }
}
