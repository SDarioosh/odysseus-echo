package com.msp1974.vacompanion.screenshots

// Odysseus fork: the Music page's Search tab (odysseus/home/music/, docs/odysseus/music.md
// "Search"): empty, and results grouped by kind, on the Echo Show 5 (also at tvdpi) and a tablet,
// dark and light. A fake backend (MusicLibraryFakes.kt); fictional data only.

import android.app.Application
import androidx.compose.ui.Modifier
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.music.MusicPage
import com.msp1974.vacompanion.odysseus.home.music.MusicTabs
import com.msp1974.vacompanion.odysseus.home.music.SearchState
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class SearchShotsTest(private val screen: Screen, private val mode: Mode) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> =
            SettledShot.parameters() + Mode.entries.map { arrayOf<Any>(ECHO_TVDPI, it) }

        private val ECHO_TVDPI = Screen("echo-tvdpi", 721, 360, "tvdpi")
    }

    @get:Rule val shots = SettledShot(screen, mode)

    private fun setUp(text: String) {
        SearchState.clear()
        LibraryFixtures.install()
        SearchState.text = text
        MusicTabs.selectedId = MusicTabs.SEARCH
    }

    @After fun tearDown() {
        MusicRepository.detach()
        SearchState.clear()
        MusicTabs.selectedId = MusicTabs.NOW_PLAYING
    }

    private fun page(name: String) =
        shots.capture("music/$name", settleMs = 1_500) { MusicPage.Content(HomePageContext(), Modifier) }

    @Test fun empty() {
        setUp("")
        page("search-empty")
    }

    @Test fun results() {
        setUp("lantern")
        page("search-results")
    }
}
