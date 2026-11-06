package com.msp1974.vacompanion.odysseus.followup

// Odysseus fork: Menu → Odysseus → "Answer without the wake word" and how long it listens
// (FollowUpSettings). Called with one line from OdysseusSettingsLayout. See docs/odysseus/follow-up.md.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.managed.ManagedNote
import com.msp1974.vacompanion.odysseus.managed.ManagedSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowUpSettingsSection() {
    FollowUpSettings.init(LocalContext.current)
    val colors = MaterialTheme.colorScheme
    // FollowUpSettings is Compose state, so this recomposes when it changes (here or from the hub).
    val enabled = FollowUpSettings.enabled
    val seconds = FollowUpSettings.seconds
    // Set by Odysseus (odysseus/managed/): the control is shown but can't be changed here.
    val enabledManaged = ManagedSettings.isManaged(FollowUpSettings.KEY_ENABLED)
    val secondsManaged = ManagedSettings.isManaged(FollowUpSettings.KEY_SECONDS)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = enabled, onCheckedChange = { FollowUpSettings.enabled = it }, enabled = !enabledManaged)
        Column(Modifier.padding(start = 14.dp)) {
            Text("Answer without the wake word", fontSize = 16.sp, color = colors.onSurface)
            Text(
                "When Odysseus asks to confirm (“Add it to your calendar?”), a soft tone, then the device " +
                    "listens for your “yes” or “no” by itself. Not while muted, in do not disturb, " +
                    "recording, or with the screen blanked.",
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            ManagedNote(FollowUpSettings.KEY_ENABLED)
        }
    }
    Text("Listen for", fontSize = 16.sp, color = colors.onSurface)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // A value Odysseus set that isn't one of the choices still shows as chosen.
        val choices = (FollowUpRules.SECONDS_CHOICES + seconds).distinct().sorted()
        choices.forEach { s ->
            FilterChip(
                selected = seconds == s,
                onClick = { FollowUpSettings.seconds = s },
                label = { Text("$s s") },
                enabled = enabled && !secondsManaged,
            )
        }
    }
    ManagedNote(FollowUpSettings.KEY_SECONDS, "$seconds s")
}
