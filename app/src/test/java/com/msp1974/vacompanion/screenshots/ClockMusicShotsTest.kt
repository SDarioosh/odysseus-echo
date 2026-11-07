package com.msp1974.vacompanion.screenshots

// Odysseus fork: every clock preset with music playing, from both "Now playing from" sources (E15,
// home/clock/ClockNowPlaying.kt): this device's Sendspin player (the kitchen's track) and the Music
// page's selected room (the living room's, from a fake MusicBackend). On the Echo Show 5 and a
// tablet. Fictional data (MusicShotFixtures). The time is the real time of the run.

import android.app.Application
import androidx.compose.ui.Modifier
import com.msp1974.vacompanion.odysseus.home.ClockPage
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.clock.ClockPresets
import com.msp1974.vacompanion.odysseus.home.clock.ClockStyle
import com.msp1974.vacompanion.odysseus.home.clock.NowPlayingSource
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import android.os.SystemClock
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
class ClockMusicShotsTest(private val screen: Screen, private val mode: Mode) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = listOf(arrayOf(Screen.EchoShow5, Mode.DARK), arrayOf(Screen.Tablet, Mode.DARK), arrayOf(Screen.EchoShow5, Mode.LIGHT))
    }

    @get:Rule val shots = SettledShot(screen, mode)

    @After fun tearDown() = ShotMusic.reset()

    /** This device's Sendspin player: the kitchen's track. */
    private fun sendspin() = SendspinNowPlaying(
        title = "Harbour Lights", artist = "The Fictional Band", album = "Songs for Nobody", artwork = gradientArt(),
        durationMs = 214_000, positionMs = 83_000, positionAtMs = SystemClock.elapsedRealtime(),
        active = true, playing = true, commands = setOf("play", "pause", "next", "previous"),
    )

    private fun clock(preset: ClockStyle, source: NowPlayingSource) {
        // The selected room plays something else than this device, so the shots tell the sources apart.
        ShotMusic.install(ShotMusic.LIVING)
        val style = ClockStyle.normalized(preset).copy(nowPlayingFrom = source)
        val context = if (source == NowPlayingSource.DEVICE) HomePageContext(nowPlaying = sendspin()) else HomePageContext()
        shots.capture("clock-music/${preset.preset}-${source.name.lowercase()}", settleMs = 1_000) {
            ClockPage.ClockStyleContent(style, context, Modifier)
        }
    }

    @Test fun classicDevice() = clock(ClockPresets.classic, NowPlayingSource.DEVICE)
    @Test fun classicRoom() = clock(ClockPresets.classic, NowPlayingSource.ROOM)
    @Test fun midnightDevice() = clock(ClockPresets.midnight, NowPlayingSource.DEVICE)
    @Test fun midnightRoom() = clock(ClockPresets.midnight, NowPlayingSource.ROOM)
    @Test fun paperDevice() = clock(ClockPresets.paper, NowPlayingSource.DEVICE)
    @Test fun paperRoom() = clock(ClockPresets.paper, NowPlayingSource.ROOM)
    @Test fun sunsetDevice() = clock(ClockPresets.sunset, NowPlayingSource.DEVICE)
    @Test fun sunsetRoom() = clock(ClockPresets.sunset, NowPlayingSource.ROOM)
    @Test fun terminalDevice() = clock(ClockPresets.terminal, NowPlayingSource.DEVICE)
    @Test fun terminalRoom() = clock(ClockPresets.terminal, NowPlayingSource.ROOM)
    @Test fun boldDevice() = clock(ClockPresets.bold, NowPlayingSource.DEVICE)
    @Test fun boldRoom() = clock(ClockPresets.bold, NowPlayingSource.ROOM)
    @Test fun albumDevice() = clock(ClockPresets.album, NowPlayingSource.DEVICE)
    @Test fun albumRoom() = clock(ClockPresets.album, NowPlayingSource.ROOM)
    @Test fun nightstandDevice() = clock(ClockPresets.nightstand, NowPlayingSource.DEVICE)
    @Test fun nightstandRoom() = clock(ClockPresets.nightstand, NowPlayingSource.ROOM)
}
