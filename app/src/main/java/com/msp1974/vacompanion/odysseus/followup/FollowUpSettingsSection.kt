package com.msp1974.vacompanion.odysseus.followup

// Odysseus fork: Menu → Odysseus → "Answer without the wake word" and how long it listens
// (FollowUpSettings). Called with one line from OdysseusSettingsLayout. See docs/odysseus/follow-up.md.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.managed.ManagedSwitchRow
import com.msp1974.vacompanion.odysseus.managed.SecondsChips

@Composable
fun FollowUpSettingsSection() {
    FollowUpSettings.init(LocalContext.current)
    // FollowUpSettings is Compose state, so this recomposes when it changes (here or from the hub).
    val enabled = FollowUpSettings.enabled
    ManagedSwitchRow(
        enabled, "Answer without the wake word",
        "When Odysseus asks to confirm (“Add it to your calendar?”), a soft tone, then the device " +
            "listens for your “yes” or “no” by itself. Not while muted, in do not disturb, " +
            "recording, or with the screen blanked.",
        managedKey = FollowUpSettings.KEY_ENABLED,
    ) { FollowUpSettings.enabled = it }
    Text("Listen for", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    SecondsChips(
        FollowUpRules.SECONDS_CHOICES, FollowUpSettings.seconds, FollowUpSettings.KEY_SECONDS, enabled = enabled,
    ) { FollowUpSettings.seconds = it }
}
