package com.msp1974.vacompanion.screenshots

// Odysseus fork: fictional Music Assistant rooms over a fake MusicBackend, installed into
// MusicRepository for the tablet Music page and clock screenshots (E15). Artwork is pushed
// (PlayerState.artwork), so no Home Assistant is needed. Fictional data only.

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
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
import org.robolectric.RuntimeEnvironment

/** A MusicBackend with fixed rooms, states and queues; every change does nothing. */
internal class ShotMusicBackend(
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

/** The fictional house: the kitchen (this device) plays, the living room is paused, the office is idle. */
internal object ShotMusic {
    const val KITCHEN = "media_player.kitchen"
    const val LIVING = "media_player.living_room"
    const val OFFICE = "media_player.office"
    const val BEDROOM = "media_player.bedroom"
    const val DOWNSTAIRS = "media_player.downstairs"

    private val all = Control.entries.toSet()

    val rooms = listOf(
        Room(KITCHEN, "Kitchen", "Kitchen", isThisDevice = true, isGroup = false, groupMembers = emptyList(), available = true),
        Room(LIVING, "Living Room", "Living Room", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true),
        Room(OFFICE, "Office", "Upstairs", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true),
        Room(BEDROOM, "Bedroom", "Bedroom", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = false),
        Room(DOWNSTAIRS, "Downstairs", null, isThisDevice = false, isGroup = true, groupMembers = listOf(KITCHEN, LIVING), available = true),
    )

    fun track(
        title: String, artist: String, album: String, art: Bitmap?, playing: Boolean,
        position: Double = 83.0, duration: Double = 214.0, volume: Float = 0.6f, repeat: Repeat = Repeat.OFF,
    ) = PlayerState(
        title = title, artist = artist, album = album, artUrl = null, artwork = art, playing = playing,
        // On the wall clock, as every PlayerState is (PlayerClock).
        durationS = duration, positionS = position, positionAtMs = System.currentTimeMillis(), shuffle = false,
        repeat = repeat, volume = volume, muted = false, can = all,
    )

    fun idle(volume: Float? = 0.35f, can: Set<Control> = all) = PlayerState(
        title = null, artist = null, album = null, artUrl = null, playing = false, durationS = null, positionS = null,
        positionAtMs = 0L, shuffle = false, repeat = Repeat.OFF, volume = volume, muted = false, can = can,
    )

    fun states(): Map<String, PlayerState> = mapOf(
        KITCHEN to track("Harbour Lights", "The Fictional Band", "Songs for Nobody", gradientArt(), playing = true, repeat = Repeat.ALL),
        LIVING to track(
            "Paper Lanterns", "Nobody in Particular", "Made-Up Weather",
            gradientArt(from = 0xFF98C379.toInt(), to = 0xFF56B6C2.toInt()), playing = true, position = 31.0, duration = 187.0, volume = 0.4f,
        ),
        OFFICE to idle(),
        BEDROOM to idle(volume = null, can = emptySet()),
        DOWNSTAIRS to idle(volume = 0.5f, can = all - Control.GROUP),
    )

    val queues = mapOf(
        KITCHEN to QueueSummary(
            current = MediaItem("library://track/1", MediaKind.TRACK, "Harbour Lights", "The Fictional Band", null),
            next = MediaItem("library://track/2", MediaKind.TRACK, "Low Tide Radio", "The Fictional Band", null),
            count = 12,
        ),
    )

    /** Installs the fake backend, attaches, and selects [room] and [tab]. */
    fun install(room: String, tab: String = MusicTabs.NOW_PLAYING, states: Map<String, PlayerState> = states()) {
        val backend = ShotMusicBackend(rooms, states, queues)
        MusicRepository.install(RuntimeEnvironment.getApplication(), backend, signedIn = { true }, area = { "Kitchen" })
        MusicRepository.attach()
        val until = System.currentTimeMillis() + 5_000
        while (MusicRepository.rooms != rooms && System.currentTimeMillis() < until) Thread.sleep(10)
        check(MusicRepository.rooms == rooms) { "rooms not loaded" }
        // selfRoom and reachable come from HaMusicBackend only; set them as it would.
        repoState<SelfMatch?>("selfRoom").value = SelfMatch(KITCHEN, How.ID)
        repoState<Boolean>("reachable").value = true
        MusicRepository.select(room)
        MusicTabs.selectedId = tab
    }

    fun reset() {
        MusicRepository.detach()
        repoState<Boolean>("reachable").value = true
        MusicTabs.selectedId = MusicTabs.NOW_PLAYING
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> repoState(name: String): MutableState<T> =
        MusicRepository::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(null) as MutableState<T>
}
