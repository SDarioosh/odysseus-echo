package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: Menu → Odysseus → Home screen → "Today page" (stored in HomeSettings). Called
// with one line from OdysseusSettingsLayout. Closing the menu re-reads the Odysseus link, so a
// device linked (or unlinked) there gains (or loses) the page at once.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.managed.ManagedNote
import com.msp1974.vacompanion.odysseus.managed.ManagedSettings

@Composable
fun TodaySettingsSection() {
    val context = LocalContext.current
    HomeSettings.init(context)
    OdysseusLink.init(context)
    DisposableEffect(Unit) {
        TodayRepository.checkLink()
        onDispose { TodayRepository.checkLink() }
    }
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = HomeSettings.todayEnabled,
            onCheckedChange = { HomeSettings.todayEnabled = it },
            enabled = HomeSettings.enabled && !ManagedSettings.isManaged("today.enabled"),
        )
        Column(Modifier.padding(start = 14.dp)) {
            Text("Today page", fontSize = 16.sp, color = colors.onSurface)
            Text(
                "Swipe left from the clock: today's calendar, to-dos due and Athena's action items, from Odysseus." +
                    if (TodayRepository.linked) "" else " Shows once this device is linked to Odysseus.",
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
            ManagedNote("today.enabled")
        }
    }
}
