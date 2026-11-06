package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: keeps the Today page's data fresh while the home screen is composed (HomeHost
// calls this; it leaves composition when the screen blanks, which stops the timer): at once,
// every 15 min, when the network comes back, and when the Today page comes into view (unless
// the data is under a minute old).

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.home.HomeDestination
import com.msp1974.vacompanion.odysseus.home.HomePages
import com.msp1974.vacompanion.odysseus.home.HomeScreenController
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import kotlinx.coroutines.delay

@Composable
fun TodayRefresh(isNetworkConnected: Boolean) {
    OdysseusLink.init(LocalContext.current)
    LaunchedEffect(Unit) { TodayRepository.checkLink() }

    val on = HomeSettings.enabled && HomeSettings.todayEnabled
    val linked = TodayRepository.linked
    LaunchedEffect(on, linked, isNetworkConnected) {
        if (!on || !linked || !isNetworkConnected) return@LaunchedEffect
        TodayRepository.refresh(minAgeMs = 2 * 60_000L)
        while (true) {
            delay(TodayRepository.REFRESH_MS)
            TodayRepository.refresh()
        }
    }

    val showing = (HomeScreenController.destination as? HomeDestination.Page)
        ?.let { HomePages.all.getOrNull(it.index) } == TodayPage
    LaunchedEffect(showing) {
        if (showing) TodayRepository.refresh(minAgeMs = 60_000L)
    }
}
