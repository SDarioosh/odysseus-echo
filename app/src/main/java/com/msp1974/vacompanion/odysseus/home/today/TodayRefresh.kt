package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: keeps the Today page's data fresh while the home screen is composed (HomeHost
// calls this; it leaves composition when the screen blanks, which stops the timer): at once,
// every 15 min, soon after midnight, when the network comes back, and when the Today page comes
// into view (unless the data is under a minute old). Without a network the result shown is
// marked stale and still dropped after 2 h or at midnight.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.home.HomeDestination
import com.msp1974.vacompanion.odysseus.home.HomePages
import com.msp1974.vacompanion.odysseus.home.HomeScreenController
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import kotlinx.coroutines.delay

/** How often the keep rules are applied and a refresh is considered. */
private const val TICK_MS = 60_000L

@Composable
fun TodayRefresh(isNetworkConnected: Boolean) {
    OdysseusLink.init(LocalContext.current)
    LaunchedEffect(Unit) { TodayRepository.checkLink() }

    val on = HomeSettings.enabled && HomeSettings.todayEnabled
    val linked = TodayRepository.linked
    LaunchedEffect(on, linked, isNetworkConnected) {
        TodayRepository.setNetwork(isNetworkConnected)
        if (!on || !linked) return@LaunchedEffect
        if (!isNetworkConnected) {
            // No fetch can work: what is shown is marked stale, and the 2 h and midnight rules
            // still apply to it.
            while (true) {
                delay(TICK_MS)
                TodayRepository.revalidate()
            }
        }
        TodayRepository.refresh(minAgeMs = 2 * 60_000L)
        while (true) {
            delay(TICK_MS)
            TodayRepository.tick()
        }
    }

    val showing = (HomeScreenController.destination as? HomeDestination.Page)
        ?.let { HomePages.all.getOrNull(it.index) } == TodayPage
    LaunchedEffect(showing) {
        if (showing) TodayRepository.refresh(minAgeMs = 60_000L)
    }
}
