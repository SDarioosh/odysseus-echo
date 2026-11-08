package com.msp1974.vacompanion.odysseus.managed

// Odysseus fork: "Set by Odysseus" under a setting the hub manages (docs/odysseus/managed-settings.md).
// The settings screen also disables that setting's control: `enabled = ... && !ManagedSettings.isManaged(key)`.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** A number of seconds as the settings menu shows it: "Never" (0), "10 min", "8 s". */
fun secondsLabel(seconds: Int): String = when {
    seconds <= 0 -> "Never"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "$seconds s"
}
