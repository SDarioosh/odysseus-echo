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
        // The provider copy of the same album (same name) is dropped; another artist's album too.
        assertEquals(listOf("library://album/21"), d.albums.map { it.uri })
        assertEquals("HUB<http://192.168.1.20:8095/imageproxy/al21>", d.albums[0].imageUrl)
        assertEquals(listOf("library://track/301", "library://track/450"), d.tracks.map { it.uri })
        assertEquals("Copper Sky (Live)", d.tracks[1].name)
    }

    @Test
    fun albumMergesBothAnswersInTrackOrder() {
        val d = HaMusicDetail.parseAlbum(
            listOf(MusicFixtures.json("detail_album_search.json"), MusicFixtures.json("detail_album_library.json")), album, image,
        )
        assertEquals(listOf("library://track/301", "library://track/302", "library://track/303"), d.tracks.map { it.uri })
        assertTrue(d.albums.isEmpty())
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
        assertEquals(listOf("library://track/301", "library://track/303"), d.tracks.map { it.uri })
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
