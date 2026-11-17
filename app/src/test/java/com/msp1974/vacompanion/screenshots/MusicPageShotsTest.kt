package com.msp1974.vacompanion.screenshots

// Odysseus fork: the Music page (odysseus/home/music/, docs/odysseus/music.md) with its Playing and
// Rooms tabs, nothing playing and Home Assistant offline, on the Echo Show 5 and a tablet, dark and
// light (docs/odysseus/screenshots.md). A fake MusicBackend feeds MusicRepository; artwork is pushed
// (PlayerState.artwork), so no Home Assistant is needed. Fictional data only.

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.LocalHomeStripPosition
import com.msp1974.vacompanion.odysseus.home.music.MusicPage
import com.msp1974.vacompanion.odysseus.home.music.MusicTabs
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.Enqueue
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.LibraryOrder
import com.msp1974.vacompanion.odysseus.music.LibraryPage
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.MusicBackend
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.QueueSummary
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val KITCHEN = "media_player.kitchen"
private const val LIVING = "media_player.living_room"
private const val OFFICE = "media_player.office"
private const val BEDROOM = "media_player.bedroom"
private const val DOWNSTAIRS = "media_player.downstairs"

/** A MusicBackend with fixed rooms, states and queues; every change does nothing. */
private class FakeMusicBackend(
    rooms: List<Room>,
    private val states: Map<String, PlayerState>,
    private val queues: Map<String, QueueSummary> = emptyMap(),
) : MusicBackend {
    override val rooms: StateFlow<List<Room>> = MutableStateFlow(rooms)
    override fun player(entityId: String): Flow<PlayerState> = states[entityId]?.let { flowOf(it) } ?: emptyFlow()
    override suspend fun queue(entityId: String): QueueSummary? = queues[entityId]
    override suspend fun library(kind: MediaKind, order: LibraryOrder, favoritesOnly: Boolean, offset: Int, limit: Int) =
        LibraryPage(emptyList(), offset, false)
    override suspend fun search(text: String, kinds: Set<MediaKind>, limit: Int): Map<MediaKind, List<MediaItem>> = emptyMap()
    override suspend fun play(item: MediaItem, roomId: String, enqueue: Enqueue, radio: Boolean) = Unit
    override suspend fun control(roomId: String, control: Control, value: Any?) = Unit
    override suspend fun transfer(fromRoomId: String, toRoomId: String) = Unit
    override suspend fun group(leaderId: String, memberIds: List<String>) = Unit
    override suspend fun ungroup(roomId: String) = Unit
}

/** One screen and theme per run ([screen], [mode]), through [SettledShot]: the page loads its state in effects. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class MusicPageShotsTest(private val screen: Screen, private val mode: Mode) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> =
            SettledShot.parameters() + Mode.entries.map { arrayOf<Any>(ECHO_TVDPI, it) }

        /** The Echo Show 5 if it reports 213 dpi (721x360 dp; see docs/odysseus/screenshots.md). */
        private val ECHO_TVDPI = Screen("echo-tvdpi", 721, 360, "tvdpi")
    }

    @get:Rule val shots = SettledShot(screen, mode)

    private val all = Control.entries.toSet()

    private val rooms = listOf(
        Room(KITCHEN, "Kitchen", "Kitchen", isThisDevice = true, isGroup = false, groupMembers = emptyList(), available = true),
        Room(LIVING, "Living Room", "Living Room", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true),
        Room(OFFICE, "Office", "Upstairs", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true),
        Room(BEDROOM, "Bedroom", "Bedroom", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = false),
        Room(DOWNSTAIRS, "Downstairs", null, isThisDevice = false, isGroup = true, groupMembers = listOf(KITCHEN, LIVING), available = true),
    )

    private fun track(
        title: String, artist: String, album: String, art: Bitmap?, playing: Boolean,
        position: Double = 83.0, duration: Double = 214.0, volume: Float = 0.6f,
        shuffle: Boolean = false, repeat: Repeat = Repeat.OFF,
    ) = PlayerState(
        title = title, artist = artist, album = album, artUrl = null, artwork = art, playing = playing,
        // On the wall clock, as every PlayerState is (PlayerClock).
        durationS = duration, positionS = position, positionAtMs = System.currentTimeMillis(), shuffle = shuffle, repeat = repeat,
        volume = volume, muted = false, can = all,
    )

    private fun idle(volume: Float? = 0.35f, can: Set<Control> = all) = PlayerState(
        title = null, artist = null, album = null, artUrl = null, playing = false, durationS = null, positionS = null,
        positionAtMs = 0L, shuffle = false, repeat = Repeat.OFF, volume = volume, muted = false, can = can,
    )

    private val kitchen = track("Harbour Lights", "The Fictional Band", "Songs for Nobody", gradientArt(), playing = true, repeat = Repeat.ALL)
    private val living = track(
        "Paper Lanterns", "Nobody in Particular", "Made-Up Weather",
        gradientArt(from = 0xFF98C379.toInt(), to = 0xFF56B6C2.toInt()), playing = false, position = 31.0, duration = 187.0, volume = 0.4f,
    )
    private val states = mapOf(
        KITCHEN to kitchen,
        LIVING to living,
        OFFICE to idle(),
        BEDROOM to idle(volume = null, can = emptySet()),
        DOWNSTAIRS to idle(volume = 0.5f, can = all - Control.GROUP),
    )
    private val queues = mapOf(
        KITCHEN to QueueSummary(
            current = MediaItem("library://track/1", MediaKind.TRACK, "Harbour Lights", "The Fictional Band", null),
            next = MediaItem("library://track/2", MediaKind.TRACK, "Low Tide Radio", "The Fictional Band", null),
            count = 12,
        ),
    )

    /** Installs the fake backend, attaches, and selects [room] and [tab]; [reachable] false: HA offline. */
    private fun setUp(
        room: String, tab: String, reachable: Boolean = true, states: Map<String, PlayerState> = this.states,
        rooms: List<Room> = this.rooms,
    ) {
        val backend = FakeMusicBackend(rooms, states, queues)
        MusicRepository.install(RuntimeEnvironment.getApplication(), backend, signedIn = { true }, area = { "Kitchen" })
        MusicRepository.attach()
        val until = System.currentTimeMillis() + 5_000
        while (MusicRepository.rooms != rooms && System.currentTimeMillis() < until) Thread.sleep(10)
        check(MusicRepository.rooms == rooms) { "rooms not loaded" }
        // selfRoom and reachable come from HaMusicBackend only; set them as it would.
        repoState<SelfMatch?>("selfRoom").value = SelfMatch(KITCHEN, How.ID)
        repoState<Boolean>("reachable").value = reachable
        MusicRepository.select(room)
        MusicTabs.selectedId = tab
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> repoState(name: String): MutableState<T> =
        MusicRepository::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(null) as MutableState<T>

    @After fun tearDown() {
        MusicRepository.detach()
        repoState<Boolean>("reachable").value = true
        MusicTabs.selectedId = MusicTabs.NOW_PLAYING
    }

    private fun page(name: String) = shots.capture("music/$name") {
        MusicPage.Content(HomePageContext(), androidx.compose.ui.Modifier) }

    @Test fun playing() {
        setUp(KITCHEN, MusicTabs.NOW_PLAYING)
        page("playing")
    }

    @Test fun playingPaused() {
        setUp(LIVING, MusicTabs.NOW_PLAYING)
        page("playing-paused")
    }

    @Test fun rooms() {
        setUp(KITCHEN, MusicTabs.ROOMS)
        page("rooms")
    }

    @Test fun nothingPlaying() {
        setUp(OFFICE, MusicTabs.NOW_PLAYING)
        page("nothing")
    }

    @Test fun nothingPlayingAnywhere() {
        setUp(KITCHEN, MusicTabs.NOW_PLAYING, states = states.mapValues { (id, s) -> if (id == KITCHEN || id == LIVING) idle() else s })
        page("nothing-anywhere")
    }

    @Test fun unavailable() {
        setUp(BEDROOM, MusicTabs.NOW_PLAYING)
        page("unavailable")
    }

    @Test fun offline() {
        setUp(KITCHEN, MusicTabs.NOW_PLAYING, reachable = false)
        page("offline")
    }

    @Test fun roomsOffline() {
        setUp(KITCHEN, MusicTabs.ROOMS, reachable = false)
        page("rooms-offline")
    }

    /**
     * Other media players beside Music Assistant's (Room.musicAssistant false): a TV playing and a
     * Cast speaker idle, with their transport and volume only (no ⋮ for Move or Group).
     */
    private val TV = "media_player.living_room_tv"
    private val CAST = "media_player.garage_cast"
    private val withOthers = rooms + listOf(
        Room(TV, "Living Room TV", "Living Room", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true, musicAssistant = false),
        Room(CAST, "Garage Cast", "Garage", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true, musicAssistant = false),
    )
    private val othersStates = states + mapOf(
        TV to track("Night Garden", "Fictional Channel", "", null, playing = true, position = 1_204.0, duration = 2_700.0, volume = 0.3f)
            .copy(can = setOf(Control.PLAY, Control.PAUSE, Control.VOLUME, Control.MUTE, Control.SEEK)),
        CAST to idle(volume = 0.5f, can = setOf(Control.PLAY, Control.PAUSE, Control.VOLUME, Control.MUTE, Control.NEXT, Control.PREVIOUS)),
    )

    @Test fun roomsWithOtherPlayers() {
        setUp(KITCHEN, MusicTabs.ROOMS, states = othersStates, rooms = withOthers)
        page("rooms-other-players")
    }

    @Test fun playingOnAnotherPlayer() {
        setUp(TV, MusicTabs.NOW_PLAYING, states = othersStates, rooms = withOthers)
        page("playing-other-player")
    }

    /** Composed beside the clock, out of sight (HomeHost's strip on page 0): the rail only, no player followed. */
    @Test fun besideTheClock() {
        setUp(KITCHEN, MusicTabs.NOW_PLAYING)
        shots.capture("music/beside-clock") {
            CompositionLocalProvider(LocalHomeStripPosition provides { 0f }) {
                MusicPage.Content(HomePageContext(), androidx.compose.ui.Modifier)
            }
        }
    }
}
