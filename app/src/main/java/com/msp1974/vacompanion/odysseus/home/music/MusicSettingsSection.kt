package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: Menu → Odysseus → Music (stored in MusicSettings): the Music page on/off, "Stay
// while playing here" (both hub-managed, contract C7) and "This device's player" (on-device only):
// Auto, showing what was found and how, or one of the rooms. Called with one line from
// OdysseusSettingsLayout.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msp1974.vacompanion.odysseus.managed.ManagedNote
import com.msp1974.vacompanion.odysseus.managed.ManagedSettings
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.MusicSettings
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch
import com.msp1974.vacompanion.ui.VAViewModel

/** How this device's player was found, as the settings say it. Pure. */
fun selfMatchText(match: SelfMatch?, rooms: List<Room>): String {
    if (match == null) return "No player found for this device"
    val name = rooms.firstOrNull { it.entityId == match.entityId }?.name ?: match.entityId
    return when (match.how) {
        How.OVERRIDE -> "$name (chosen here)"
        How.ID -> "$name (found by its player id)"
        How.LEGACY_ID -> "$name (found by its old player id)"
        How.NAME -> "$name (found by its name)"
        How.AREA -> "$name? (a guess from this device's area)"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MusicSettingsSection() {
    val context = LocalContext.current
    MusicSettings.init(context)
    val vm: VAViewModel = viewModel()
    ensureMusicInstalled(context, vm.deviceManager)
    LaunchedEffect(Unit) { MusicRepository.probe(minAgeMs = 60_000L) }

    val colors = MaterialTheme.colorScheme
    val enabled = MusicSettings.enabled
    Text("Music", fontSize = 18.sp, color = colors.primary, modifier = Modifier.padding(top = 12.dp))

    SwitchRow(
        enabled, MusicSettings.KEY_ENABLED, "Music page",
        "Right of the Today page: what plays in each room, volume, moving and grouping, from Music Assistant through Home Assistant." +
            if (MusicRepository.available) "" else " Shows once Home Assistant is signed in and Music Assistant has players.",
        enabled = true,
    ) { MusicSettings.enabled = it }
    SwitchRow(
        MusicSettings.stayWhilePlaying, MusicSettings.KEY_STAY_WHILE_PLAYING, "Stay while playing here",
        "The Music page doesn't go back to the clock while something plays on this device.",
        enabled = enabled,
    ) { MusicSettings.stayWhilePlaying = it }

    // "This device's player" (contract I3): Auto, or one of the rooms.
    val rooms = MusicRepository.rooms
    val override = MusicSettings.selfOverride
    val match = MusicRepository.selfRoom
    Text("This device's player", fontSize = 16.sp, color = colors.onSurface)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = override.isBlank(),
            onClick = { MusicRepository.setSelfOverride(null) },
            label = { Text("Auto") },
            enabled = enabled,
        )
        // The chosen player stays listed even when Home Assistant no longer reports it.
        val shown = if (override.isBlank() || rooms.any { it.entityId == override }) rooms.map { it.entityId to it.name }
        else rooms.map { it.entityId to it.name } + (override to "$override (gone)")
        shown.forEach { (id, name) ->
            FilterChip(
                selected = override == id,
                onClick = { MusicRepository.setSelfOverride(id) },
                label = { Text(name) },
                enabled = enabled,
            )
        }
    }
    val stale = override.isNotBlank() && (match?.staleOverride == true || (rooms.isNotEmpty() && rooms.none { it.entityId == override }))
    Text(
        when {
            rooms.isEmpty() -> "No Music Assistant players seen yet. They are read from Home Assistant."
            override.isBlank() -> "Auto: " + selfMatchText(match, rooms)
            else -> "Now: " + selfMatchText(match, rooms)
        },
        fontSize = 13.sp, color = colors.onSurfaceVariant,
    )
    if (stale) {
        Text(
            "The chosen player is no longer in Home Assistant; Auto is used until you choose again.",
            fontSize = 13.sp, color = colors.error,
        )
    }
}

@Composable
private fun SwitchRow(
    checked: Boolean, key: String, title: String, detail: String, enabled: Boolean, onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled && !ManagedSettings.isManaged(key))
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, fontSize = 16.sp, color = colors.onSurface)
            Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant)
            ManagedNote(key)
        }
    }
}
