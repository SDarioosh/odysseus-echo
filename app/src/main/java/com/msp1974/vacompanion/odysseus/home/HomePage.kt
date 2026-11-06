package com.msp1974.vacompanion.odysseus.home

// Odysseus fork: the native pages of the home screen. To add a page (weather, calendar, Athena's
// daily digest, …) write an object implementing HomePage in this package and add it to
// HomePages.all; navigation, idle return and animation come from HomeHost and need no change.
// Pages get what they show through HomePageContext, never by reaching into the view model, so
// they can be previewed and tested on their own.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import com.msp1974.vacompanion.odysseus.done.DoneMarks
import com.msp1974.vacompanion.odysseus.done.DoneTarget
import com.msp1974.vacompanion.odysseus.home.today.TodayPage
import com.msp1974.vacompanion.odysseus.home.today.TodayView
import com.msp1974.vacompanion.odysseus.home.weather.WeatherNow
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying

/** Everything a page may show or do. Add fields here when a new page needs more. */
@Immutable
data class HomePageContext(
    val nowPlaying: SendspinNowPlaying = SendspinNowPlaying(),
    val isNetworkConnected: Boolean = true,
    /** Home Assistant is connected to this device. */
    val isServerConnected: Boolean = true,
    val isDND: Boolean = false,
    /** Current weather from Home Assistant; null = don't show any. */
    val weather: WeatherNow? = null,
    /** The Today page's data from Odysseus (today/TodayRepository). */
    val today: TodayView = TodayView.NotLinked,
    /** The Today page's Done marks (today/TodayDone). */
    val todayDone: DoneMarks = DoneMarks(),
    /** Marks a Today row done: target, its title and its place in its list. */
    val onTodayDone: (DoneTarget, String, Int) -> Unit = { _, _, _ -> },
    /** Undoes a Today row's done. */
    val onTodayUndo: (DoneTarget) -> Unit = {},
    /** Sends a Sendspin transport command ("play", "pause", "next", …). */
    val onSendspinCommand: (String) -> Unit = {},
)

interface HomePage {
    /** Stable id, for logs and future settings (e.g. hiding a page). */
    val id: String

    /**
     * Draws the page full screen. The page must not handle horizontal swipes itself: HomeHost
     * does. Taps and vertical gestures are fine.
     */
    @Composable
    fun Content(context: HomePageContext, modifier: Modifier)
}

object HomePages {
    /**
     * Left to right. Index 0 is where the idle timer returns to. A getter, not a fixed list: a page
     * can come and go with its setting (reads Compose state, so readers recompose).
     */
    val all: List<HomePage>
        get() = if (TodayPage.available) listOf(ClockPage, TodayPage) else listOf(ClockPage)
}
