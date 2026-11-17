package com.msp1974.vacompanion.odysseus.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaMusicDetailTest {
    private val image: (String) -> String? = { "HUB<$it>" }
    private val artist = MediaItem("library://artist/7", MediaKind.ARTIST, "The Copper Lanterns", "", null)
    private val album = MediaItem("library://album/21", MediaKind.ALBUM, "Harbour Lights (Deluxe)", "The Copper Lanterns", null)

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun normStripsVersionsAndCase() {
        assertEquals("harbour lights", HaMusicDetail.norm("  Harbour Lights (Deluxe) [Remastered] "))
        assertEquals("(intro)", HaMusicDetail.norm("(Intro)"))
        assertEquals("", HaMusicDetail.norm(null))
        assertEquals("Harbour Lights", HaMusicDetail.queryName(album))
    }

    @Test
    fun calls() {
        assertEquals(
            obj("""{"config_entry_id":"E1","name":"The Copper Lanterns","media_type":["album","track"],"limit":25}"""),
            HaMusicDetail.artistCall("E1", artist).data,
        )
        assertEquals(
            obj("""{"config_entry_id":"E1","name":"Harbour Lights","media_type":["track"],"limit":50}"""),
            HaMusicDetail.albumSearchCall("E1", album).data,
        )
        val lib = HaMusicDetail.albumLibraryCall("E1", album)
        assertEquals("get_library", lib.service)
        assertEquals(
            obj("""{"config_entry_id":"E1","media_type":"track","search":"Harbour Lights","order_by":"sort_name","offset":0,"limit":100}"""),
            lib.data,
        )
    }

    @Test
    fun artistKeepsOnlyItsOwnAlbumsAndTracks() {
        val d = HaMusicDetail.parseArtist(MusicFixtures.json("detail_artist_search.json"), artist, image)
        assertTrue(d.listed)
        // Another artist's album is dropped; a different version ("(Deluxe)") of the album is kept.
        assertEquals(listOf("library://album/21", "fictional://album/x21"), d.albums.map { it.uri })
        assertEquals("HUB<http://192.168.1.20:8095/imageproxy/al21>", d.albums[0].imageUrl)
        assertEquals(listOf("library://track/301", "library://track/450"), d.tracks.map { it.uri })
        assertEquals("Copper Sky (Live)", d.tracks[1].name)
    }

    @Test
    fun albumMergesBothAnswersInTheOrderFound() {
        val d = HaMusicDetail.parseAlbum(
            listOf(MusicFixtures.json("detail_album_search.json"), MusicFixtures.json("detail_album_library.json")), album, image,
        )
        // MA's order (HA gives no track numbers), each track once.
        assertEquals(listOf("library://track/303", "library://track/301", "library://track/302"), d.tracks.map { it.uri })
        assertTrue(d.albums.isEmpty())
    }

    private fun track(uri: String, name: String, album: String, albumUri: String, artist: String, version: String = "") =
        """{"uri":"$uri","media_type":"track","name":"$name","version":"$version",
            "album":{"uri":"$albumUri","name":"$album"},"artists":[{"name":"$artist"}]}"""

    @Test
    fun albumKeepsTracksThatShareABaseName() {
        val glass = MediaItem("library://album/1", MediaKind.ALBUM, "Glass Rooms", "Fictional Band", null)
        val r = Json.parseToJsonElement("""{"tracks":[
            ${track("library://track/1", "Long Song (Parts I-V)", "Glass Rooms", "library://album/1", "Fictional Band")},
            ${track("library://track/2", "Interlude", "Glass Rooms", "library://album/1", "Fictional Band")},
            ${track("library://track/3", "Long Song (Parts VI-IX)", "Glass Rooms", "library://album/1", "Fictional Band")},
            ${track("library://track/4", "Shine", "Glass Rooms", "library://album/1", "Fictional Band", version = "Part 1")},
            ${track("library://track/5", "Shine", "Glass Rooms", "library://album/1", "Fictional Band", version = "Part 2")},
            ${track("library://track/6", "Intro", "Glass Rooms", "library://album/1", "Fictional Band")},
            ${track("library://track/7", "Intro (Reprise)", "Glass Rooms", "library://album/1", "Fictional Band")},
            ${track("library://track/2", "Interlude", "Glass Rooms", "library://album/1", "Fictional Band")}
        ]}""")
        assertEquals(
            listOf("Long Song (Parts I-V)", "Interlude", "Long Song (Parts VI-IX)", "Shine (Part 1)", "Shine (Part 2)", "Intro", "Intro (Reprise)"),
            HaMusicDetail.parseAlbum(listOf(r), glass, image).tracks.map { it.name },
        )
    }

    @Test
    fun albumLeavesOutANamesakeAlbumOfAnotherArtist() {
        val hits = MediaItem("library://album/1", MediaKind.ALBUM, "Greatest Hits", "Fictional Band", null)
        val r = Json.parseToJsonElement("""{"tracks":[
            ${track("spotify://track/a", "Song A", "Greatest Hits", "spotify://album/x", "Fictional Band")},
            ${track("spotify://track/b", "Song B", "Greatest Hits", "spotify://album/y", "Other Band")},
            ${track("library://track/c", "Song C", "Greatest Hits", "library://album/1", "Guest Singer")}
        ]}""")
        // Same uri: always this album (a guest's track); same name: only with one of its artists.
        assertEquals(listOf("Song A", "Song C"), HaMusicDetail.parseAlbum(listOf(r), hits, image).tracks.map { it.name })
    }

    @Test
    fun artistKeepsLiveVersionsOfASong() {
        val band = MediaItem("library://artist/1", MediaKind.ARTIST, "Fictional Band", "", null)
        val r = Json.parseToJsonElement("""{"tracks":[
            ${track("library://track/1", "Copper Sky", "A", "library://album/1", "Fictional Band")},
            ${track("library://track/2", "Copper Sky", "B", "library://album/2", "Fictional Band", version = "Live")},
            ${track("library://track/3", "Copper Sky", "C", "library://album/3", "Fictional Band", version = "Remix")},
            ${track("library://track/1", "Copper Sky", "A", "library://album/1", "Fictional Band")}
        ]}""")
        assertEquals(
            listOf("Copper Sky", "Copper Sky (Live)", "Copper Sky (Remix)"),
            HaMusicDetail.parseArtist(r, band, image).tracks.map { it.name },
        )
    }

    @Test
    fun emptyAnswers() {
        assertTrue(HaMusicDetail.parseAlbum(emptyList(), album, image).tracks.isEmpty())
        assertTrue(HaMusicDetail.parseArtist(null, artist, image).albums.isEmpty())
    }
}

class HaMusicBackendDetailTest {
    private val ha = FakeHaApi().apply { templateAnswer = { MusicFixtures.text("rooms_template.json") } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun backend() = HaMusicBackend(
        ha = ha, selfClientId = { null }, area = { null }, isLocked = { true }, imageUrl = { it }, scope = scope,
    )

    @After fun tearDown() = scope.cancel()

    @Test
    fun albumAsksSearchThenLibrary() = runBlocking {
        ha.answers["music_assistant.search"] = { MusicFixtures.json("detail_album_search.json") }
        ha.answers["music_assistant.get_library"] = { MusicFixtures.json("detail_album_library.json") }
        val album = MediaItem("library://album/21", MediaKind.ALBUM, "Harbour Lights", "", null)
        val d = backend().detail(album)
        assertEquals(3, d.tracks.size)
        assertEquals(listOf("search", "get_library"), ha.calls.map { it.service })
        assertTrue(ha.calls.all { it.returnResponse })
    }

    @Test
    fun albumWithoutLibraryAnswerStillLists() = runBlocking {
        ha.answers["music_assistant.search"] = { MusicFixtures.json("detail_album_search.json") }
        ha.answers["music_assistant.get_library"] = { throw IllegalStateException("fictional failure") }
        val d = backend().detail(MediaItem("library://album/21", MediaKind.ALBUM, "Harbour Lights", "", null))
        assertEquals(listOf("library://track/303", "library://track/301"), d.tracks.map { it.uri })
    }

    @Test
    fun readingWorksWhileLockedAndPlaylistsAreNotListed() = runBlocking {
        ha.answers["music_assistant.search"] = { MusicFixtures.json("detail_artist_search.json") }
        val artist = MediaItem("library://artist/7", MediaKind.ARTIST, "The Copper Lanterns", "", null)
        assertEquals(2, backend().detail(artist).tracks.size)
        val playlist = backend().detail(MediaItem("library://playlist/3", MediaKind.PLAYLIST, "Mix", "", null))
        assertFalse(playlist.listed)
        assertEquals(1, ha.calls.size)
    }
}
