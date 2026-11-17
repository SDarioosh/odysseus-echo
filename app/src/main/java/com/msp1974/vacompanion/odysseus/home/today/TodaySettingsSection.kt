package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: Menu → Odysseus → Home screen → "Today page" (stored in HomeSettings). Called
// with one line from OdysseusSettingsLayout. Closing the menu re-reads the Odysseus link, so a
// device linked (or unlinked) there gains (or loses) the page at once.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.managed.ManagedSwitchRow

@Composable
fun TodaySettingsSection() {
    val context = LocalContext.current
    HomeSettings.init(context)
    OdysseusLink.init(context)
    DisposableEffect(Unit) {
        TodayRepository.checkLink()
        onDispose { TodayRepository.checkLink() }
    }
    ManagedSwitchRow(
        HomeSettings.todayEnabled, "Today page",
        "Swipe left from the clock: today's calendar, to-dos due and Athena's action items, from Odysseus." +
            if (TodayRepository.linked) "" else " Shows once this device is linked to Odysseus.",
        managedKey = "today.enabled", enabled = HomeSettings.enabled,
    ) { HomeSettings.todayEnabled = it }
}
