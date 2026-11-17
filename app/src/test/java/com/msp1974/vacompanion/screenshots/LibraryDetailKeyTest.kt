package com.msp1974.vacompanion.screenshots

// Odysseus fork: an album opened from an artist's detail shows the album, not the artist kept from
// the same composition slot (docs/odysseus/music.md, "Library"). Robolectric; runs with -Pscreenshots.

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.msp1974.vacompanion.odysseus.home.music.LibraryState
import com.msp1974.vacompanion.odysseus.home.music.LibraryTab
import com.msp1974.vacompanion.odysseus.home.music.resetAll
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w800dp-h480dp")
class LibraryDetailKeyTest {
    @get:Rule val rule = createComposeRule()

    @After fun tearDown() {
        MusicRepository.detach()
        LibraryState.resetAll()
    }

    @Test fun albumOpenedFromArtistShowsAlbumTracks() {
        LibraryState.resetAll()
        LibraryFixtures.install()
        LibraryState.stack.add(LibraryFixtures.artist)
        rule.setContent { LibraryTab() }
        rule.waitForIdle()
        assertEquals("artist detail shows 'Tracks'", 1, rule.onAllNodesWithText("Tracks").fetchSemanticsNodes().size)
        rule.runOnIdle { LibraryState.stack.add(LibraryFixtures.album) }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        val six = rule.onAllNodesWithText("6 tracks").fetchSemanticsNodes().size
        val four = rule.onAllNodesWithText("4 tracks").fetchSemanticsNodes().size
        assertEquals("album detail shows the album's 6 tracks, not the artist's 4 (the stale detail)", Pair(1, 0), Pair(six, four))
        // Back returns to the artist's detail.
        rule.runOnIdle { LibraryState.stack.removeAt(LibraryState.stack.lastIndex) }
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        assertEquals("back on the artist", Pair(1, 0), Pair(rule.onAllNodesWithText("Tracks").fetchSemanticsNodes().size, rule.onAllNodesWithText("6 tracks").fetchSemanticsNodes().size))
    }
}
