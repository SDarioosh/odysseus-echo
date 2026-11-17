package com.msp1974.vacompanion.screenshots

// Odysseus fork: fictional Music Assistant library data and a fake backend for the Library and
// Search screenshots (LibraryShotsTest, SearchShotsTest). Thumbnails are seeded into
// MusicImages' memory cache, so nothing is fetched. Fictional names only.

import androidx.compose.runtime.MutableState
import com.msp1974.vacompanion.odysseus.home.music.MusicDetails
import com.msp1974.vacompanion.odysseus.home.music.MusicImages
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.Enqueue
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.LibraryOrder
import com.msp1974.vacompanion.odysseus.music.LibraryPage
import com.msp1974.vacompanion.odysseus.music.MediaDetail
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.MusicBackend
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.QueueSummary
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.robolectric.RuntimeEnvironment

object LibraryFixtures {
    const val KITCHEN = "media_player.kitchen"
    const val OFFICE = "media_player.office"
    const val LIVING = "media_player.living_room"

    val rooms = listOf(
        Room(KITCHEN, "Kitchen", "Kitchen", isThisDevice = true, isGroup = false, groupMembers = emptyList(), available = true),
        Room(LIVING, "Living Room", "Living Room", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true),
        Room(OFFICE, "Office", "Upstairs", isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true),
    )

    private fun img(n: String) = "https://hub.example.test/music/image?u=$n&size=256"

    val artist = MediaItem("library://artist/7", MediaKind.ARTIST, "The Copper Lanterns", "", img("ar7"), favorite = true)
    val album = MediaItem("library://album/21", MediaKind.ALBUM, "Harbour Lights (Deluxe)", "The Copper Lanterns", img("al21"), favorite = true)

    val albums = listOf(
        album,
        MediaItem("library://album/22", MediaKind.ALBUM, "Made-Up Weather", "Nobody in Particular", img("al22")),
        MediaItem("library://album/23", MediaKind.ALBUM, "Songs for Nobody", "The Fictional Band", img("al23"), favorite = true),
        MediaItem("library://album/24", MediaKind.ALBUM, "Paper Lanterns and Other Very Long Album Titles", "Ada Brightwater, The Copper Lanterns", null),
        MediaItem("library://album/25", MediaKind.ALBUM, "Night Ferries", "Harbour Choir", img("al25")),
        MediaItem("library://album/26", MediaKind.ALBUM, "Low Tide Radio", "The Fictional Band", img("al26")),
        MediaItem("library://album/27", MediaKind.ALBUM, "Quiet Rooms", "Ada Brightwater", null),
        MediaItem("library://album/28", MediaKind.ALBUM, "Breakwater", "The Copper Lanterns", img("al28")),
        MediaItem("library://album/29", MediaKind.ALBUM, "Lighthouse Keepers", "Nobody in Particular", img("al29")),
        MediaItem("library://album/30", MediaKind.ALBUM, "Grey Morning", "Harbour Choir", img("al30")),
        MediaItem("library://album/31", MediaKind.ALBUM, "Small Hours", "The Fictional Band", img("al31")),
        MediaItem("library://album/32", MediaKind.ALBUM, "Second Sight", "Ada Brightwater", img("al32")),
        MediaItem("library://album/33", MediaKind.ALBUM, "Undertow", "The Copper Lanterns", null),
        MediaItem("library://album/34", MediaKind.ALBUM, "Seven Bridges", "Harbour Choir", img("al34")),
    )

    val playlists = listOf(
        MediaItem("library://playlist/1", MediaKind.PLAYLIST, "Kitchen Mornings", "Owner", img("pl1"), favorite = true),
        MediaItem("library://playlist/2", MediaKind.PLAYLIST, "Rainy Sunday", "Owner", img("pl2")),
        MediaItem("library://playlist/3", MediaKind.PLAYLIST, "Dinner Party", "Owner", null),
        MediaItem("library://playlist/4", MediaKind.PLAYLIST, "Focus", "Fictional Radio", img("pl4")),
        MediaItem("library://playlist/5", MediaKind.PLAYLIST, "Road Trip 2026", "Owner", img("pl5")),
    )

    val tracks = listOf(
        MediaItem("library://track/301", MediaKind.TRACK, "Lantern Song", "The Copper Lanterns · Harbour Lights", null),
        MediaItem("library://track/302", MediaKind.TRACK, "Breakwater", "The Copper Lanterns · Harbour Lights", null),
        MediaItem("library://track/303", MediaKind.TRACK, "Low Tide", "The Copper Lanterns · Harbour Lights", null, favorite = true),
        MediaItem("library://track/304", MediaKind.TRACK, "Copper Sky (Live)", "The Copper Lanterns · Harbour Lights", null),
        MediaItem("library://track/305", MediaKind.TRACK, "Harbour Lights", "The Copper Lanterns · Harbour Lights", null),
        MediaItem("library://track/306", MediaKind.TRACK, "The Long Way Home", "The Copper Lanterns · Harbour Lights", null),
    )

    val radio = listOf(MediaItem("radiobrowser://radio/fict-1", MediaKind.RADIO, "Harbour FM", "", img("r1")))

    val artistDetail = MediaDetail(albums = albums.filter { it.subtitle.contains("Copper") }, tracks = tracks.take(4), listed = true)
    val albumDetail = MediaDetail(tracks = tracks, listed = true)

    val searchResults = mapOf(
        MediaKind.ARTIST to listOf(artist),
        MediaKind.ALBUM to albums.filter { it.subtitle.contains("Copper") },
        MediaKind.TRACK to tracks.take(3),
        MediaKind.PLAYLIST to emptyList(),
        MediaKind.RADIO to radio,
    )

    /** Every image above, as a gradient in each size bucket the tabs ask for. */
    fun seedImages() {
        val urls = (listOf(artist) + albums + playlists + radio).mapNotNull { it.imageUrl }
        urls.forEachIndexed { i, url ->
            val art = gradientArt(256, from = COLORS[i % COLORS.size], to = COLORS[(i + 3) % COLORS.size])
            for (px in listOf(64, 128, 256)) MusicImages.memory.put("$px|$url", art)
        }
        MusicDetails.cache.put(artist.uri, artistDetail)
        MusicDetails.cache.put(album.uri, albumDetail)
    }

    private val COLORS = listOf(
        0xFFE06C75.toInt(), 0xFF61AFEF.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(),
    )

    /** Installs [FakeLibraryBackend], attaches, makes the kitchen this device's room and selects it. */
    fun install() {
        MusicRepository.install(RuntimeEnvironment.getApplication(), FakeLibraryBackend(), signedIn = { true }, area = { "Kitchen" })
        MusicRepository.attach()
        val until = System.currentTimeMillis() + 5_000
        while (MusicRepository.rooms != rooms && System.currentTimeMillis() < until) Thread.sleep(10)
        check(MusicRepository.rooms == rooms) { "rooms not loaded" }
        repoState<SelfMatch?>("selfRoom").value = SelfMatch(KITCHEN, How.ID)
        MusicRepository.select(KITCHEN)
        seedImages()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> repoState(name: String): MutableState<T> =
        MusicRepository::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(null) as MutableState<T>

    /** Waits (real time) until [check] holds; the library loads on a background scope. */
    fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < until) Thread.sleep(10)
        check(check()) { "$what not loaded" }
    }

}

/** The fixtures above as a MusicBackend; changes do nothing. */
class FakeLibraryBackend : MusicBackend {
    override val rooms: StateFlow<List<Room>> = MutableStateFlow(LibraryFixtures.rooms)
    override fun player(entityId: String): Flow<PlayerState> = emptyFlow()
    override suspend fun queue(entityId: String): QueueSummary? = null
    override suspend fun library(kind: MediaKind, order: LibraryOrder, favoritesOnly: Boolean, offset: Int, limit: Int): LibraryPage {
        val all = when (kind) {
            MediaKind.ALBUM -> LibraryFixtures.albums
            MediaKind.PLAYLIST -> LibraryFixtures.playlists
            MediaKind.TRACK -> LibraryFixtures.tracks
            MediaKind.RADIO -> LibraryFixtures.radio
            MediaKind.ARTIST -> listOf(LibraryFixtures.artist)
            else -> emptyList()
        }.filter { !favoritesOnly || it.favorite }
        return LibraryPage(all.drop(offset).take(limit), offset, false)
    }
    override suspend fun search(text: String, kinds: Set<MediaKind>, limit: Int) = LibraryFixtures.searchResults
    override suspend fun detail(item: MediaItem): MediaDetail = when (item.uri) {
        LibraryFixtures.artist.uri -> LibraryFixtures.artistDetail
        LibraryFixtures.album.uri -> LibraryFixtures.albumDetail
        else -> MediaDetail()
    }
    override suspend fun play(item: MediaItem, roomId: String, enqueue: Enqueue, radio: Boolean) = Unit
    override suspend fun control(roomId: String, control: Control, value: Any?) = Unit
    override suspend fun transfer(fromRoomId: String, toRoomId: String) = Unit
    override suspend fun group(leaderId: String, memberIds: List<String>) = Unit
    override suspend fun ungroup(roomId: String) = Unit
}
