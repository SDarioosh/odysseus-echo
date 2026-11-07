package com.msp1974.vacompanion.screenshots

// Odysseus fork: the Music page's Library tab, its detail views and the play menu
// (odysseus/home/music/, docs/odysseus/music.md "Library") on the Echo Show 5 (also at tvdpi) and a
// tablet, dark and light. A fake backend (MusicLibraryFakes.kt); fictional data only.

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.music.LibraryQuery
import com.msp1974.vacompanion.odysseus.home.music.LibraryState
import com.msp1974.vacompanion.odysseus.home.music.MusicPage
import com.msp1974.vacompanion.odysseus.home.music.MusicTabs
import com.msp1974.vacompanion.odysseus.home.music.PlayMenuContent
import com.msp1974.vacompanion.odysseus.home.music.resetAll
import com.msp1974.vacompanion.odysseus.music.LibraryOrder
import com.msp1974.vacompanion.odysseus.music.MediaKind
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
class LibraryShotsTest(private val screen: Screen, private val mode: Mode) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> =
            SettledShot.parameters() + Mode.entries.map { arrayOf<Any>(ECHO_TVDPI, it) }

        private val ECHO_TVDPI = Screen("echo-tvdpi", 721, 360, "tvdpi")
    }

    @get:Rule val shots = SettledShot(screen, mode)

    /** The Library tab on [kind], loaded (the fake answers at once, on a background scope). */
    private fun setUp(kind: MediaKind, favoritesOnly: Boolean = false) {
        LibraryState.resetAll()
        LibraryFixtures.install()
        LibraryState.kind = kind
        LibraryState.favoritesOnly = favoritesOnly
        val q = LibraryQuery(kind, LibraryOrder.RECENT, favoritesOnly)
        LibraryState.loadMore(q)
        LibraryFixtures.waitFor("library") { !LibraryState.list(q).hasMore && !LibraryState.list(q).loading }
        MusicTabs.selectedId = MusicTabs.LIBRARY
    }

    @After fun tearDown() {
        MusicRepository.detach()
        LibraryState.resetAll()
        MusicTabs.selectedId = MusicTabs.NOW_PLAYING
    }

    private fun page(name: String) = shots.capture("music/$name") { MusicPage.Content(HomePageContext(), Modifier) }

    @Test fun albums() {
        setUp(MediaKind.ALBUM)
        page("library-albums")
    }

    @Test fun playlists() {
        setUp(MediaKind.PLAYLIST)
        page("library-playlists")
    }

    @Test fun noFavourites() {
        setUp(MediaKind.RADIO, favoritesOnly = true)
        page("library-empty")
    }

    @Test fun artist() {
        setUp(MediaKind.ARTIST)
        LibraryState.stack.add(LibraryFixtures.artist)
        page("library-artist")
    }

    @Test fun album() {
        setUp(MediaKind.ALBUM)
        LibraryState.stack.add(LibraryFixtures.album)
        page("library-album")
    }

    @Test fun playMenu() {
        setUp(MediaKind.ALBUM)
        shots.capture("music/play-menu") {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                PlayMenuContent(LibraryFixtures.tracks.first()) {}
            }
        }
    }
}
