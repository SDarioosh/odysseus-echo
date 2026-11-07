package com.msp1974.vacompanion.screenshots

// Odysseus fork: the Music page's layout by screen size (E15, MusicLayout): one pane on the Echo
// Show 5 (960x480 dp at mdpi, 721x360 at tvdpi), two from 1100x600 dp (1280x800 and 1920x1200
// tablets: the tab, and Now playing for the selected room on the right). Dark and light.
// Fictional data (MusicShotFixtures).

import android.app.Application
import androidx.compose.ui.Modifier
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.music.MusicPage
import com.msp1974.vacompanion.odysseus.home.music.MusicTabs
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The four screens E15 checks: the Echo Show 5 at mdpi and tvdpi, a 1280x800 and a 1920x1200 tablet. */
internal val LAYOUT_SCREENS = listOf(
    Screen.EchoShow5,
    Screen("echo-tvdpi", 721, 360, "tvdpi"),
    Screen.Tablet,
    Screen("tablet-xl", 1920, 1200),
)

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class MusicTabletShotsTest(private val screen: Screen, private val mode: Mode) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = LAYOUT_SCREENS.flatMap { s -> Mode.entries.map { arrayOf<Any>(s, it) } }
    }

    @get:Rule val shots = SettledShot(screen, mode)

    @After fun tearDown() = ShotMusic.reset()

    private fun page(name: String) = shots.capture("music-layout/$name") { MusicPage.Content(HomePageContext(), Modifier) }

    @Test fun playing() {
        ShotMusic.install(ShotMusic.KITCHEN, MusicTabs.NOW_PLAYING)
        page("playing")
    }

    @Test fun rooms() {
        ShotMusic.install(ShotMusic.KITCHEN, MusicTabs.ROOMS)
        page("rooms")
    }

    @Test fun roomsOtherRoom() {
        ShotMusic.install(ShotMusic.LIVING, MusicTabs.ROOMS)
        page("rooms-living")
    }

    @Test fun roomsNothing() {
        ShotMusic.install(ShotMusic.OFFICE, MusicTabs.ROOMS)
        page("rooms-nothing")
    }
}
