package com.msp1974.vacompanion.odysseus.managed

// Odysseus fork: the settings menu's controls for settings Odysseus may manage
// (docs/odysseus/managed-settings.md): while a key is managed its control is disabled and
// "Set by Odysseus" shows beneath it. `managedKey = null`: a setting only the device decides.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val SET_BY_ODYSSEUS = "Set by Odysseus"

/**
 * Shows "Set by Odysseus" while [key] is managed; nothing otherwise. [value] (e.g. "15 min") is
 * added for choices whose managed value may not be one of the chips offered.
 */
@Composable
fun ManagedNote(key: String, value: String? = null) {
    if (ManagedSettings.isManaged(key)) {
        Text(
            if (value == null) SET_BY_ODYSSEUS else "$SET_BY_ODYSSEUS: $value",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** True unless [managedKey] is set by Odysseus. Reads Compose state. */
private fun notManaged(managedKey: String?): Boolean = managedKey == null || !ManagedSettings.isManaged(managedKey)

/** A switch with a title and a line of detail; [extra] adds lines under the detail. */
@Composable
fun ManagedSwitchRow(
    checked: Boolean,
    title: String,
    detail: String,
    managedKey: String? = null,
    enabled: Boolean = true,
    extra: @Composable ColumnScope.() -> Unit = {},
    onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled && notManaged(managedKey))
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, fontSize = 16.sp, color = colors.onSurface)
            Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant)
            extra()
            if (managedKey != null) ManagedNote(managedKey)
        }
    }
}

/**
 * Chips for a number of seconds ([choices], 0 = never). A [value] that isn't one of them (set by
 * Odysseus) is added as one more chip, selected. "Set by Odysseus: <value>" beneath while managed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SecondsChips(
    choices: List<Int>,
    value: Int,
    managedKey: String,
    enabled: Boolean = true,
    label: (Int) -> String = ::secondsLabel,
    onPick: (Int) -> Unit,
) {
    val canChange = enabled && notManaged(managedKey)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        secondsChoices(choices, value).forEach { s ->
            FilterChip(
                selected = value == s,
                onClick = { onPick(s) },
                label = { Text(label(s)) },
                enabled = canChange,
            )
        }
    }
    ManagedNote(managedKey, label(value))
}

/** [choices] plus [value] when it isn't one of them, in order with "never" (0) last. Pure. */
fun secondsChoices(choices: List<Int>, value: Int): List<Int> =
    if (value in choices) choices else (choices + value).sortedBy { if (it <= 0) Int.MAX_VALUE else it }

/** A number of seconds as the settings menu shows it: "Never" (0), "10 min", "8 s". Pure. */
fun secondsLabel(seconds: Int): String = when {
    seconds <= 0 -> "Never"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "$seconds s"
}
