package com.msp1974.vacompanion.odysseus.player

// Odysseus fork: sizes for a player by the width it is given, and track times as text; copied from
// the upstream ui/components/NowPlayingOverlay.kt (left unchanged) so the Music page matches it.

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Sizes for every part of a player, derived from its width so the artwork, buttons, text and
 * spacing grow together on a large screen. Breakpoints 420 dp ([roomy]) and 640 dp ([large]).
 */
class PlayerMetrics(width: Dp) {
    val padding = (width * 0.03f).coerceIn(14.dp, 28.dp)
    val artwork = (width * 0.17f).coerceIn(80.dp, 168.dp)
    val artworkRadius = artwork * 0.14f
    val cardRadius = (width * 0.04f).coerceIn(24.dp, 36.dp)
    val playButton = (width * 0.075f).coerceIn(52.dp, 76.dp)
    val progressHeight = if (width < 420.dp) 4.dp else if (width < 640.dp) 5.dp else 6.dp
    val roomy = width >= 420.dp
    val large = width >= 640.dp
}

/** A track time as "m:ss", or "h:mm:ss" from an hour; negative counts as 0. */
fun formatPlayerTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
