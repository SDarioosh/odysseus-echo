package com.msp1974.vacompanion.odysseus.player

// Odysseus fork: a room's name as a chip (the Music page's room picker), marked while selected and
// with a note while that room plays.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RoomChip(
    name: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    playing: Boolean = false,
    available: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val content = if (selected) colors.onPrimary else colors.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .alpha(if (available) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) colors.primary else colors.onBackground.copy(alpha = 0.08f), shape)
            .border(1.dp, if (selected) colors.primary else colors.onBackground.copy(alpha = 0.18f), shape)
            .then(if (onClick != null && available) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (playing) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(name, fontSize = 15.sp, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
