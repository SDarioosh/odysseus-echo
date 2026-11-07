package com.msp1974.vacompanion.odysseus.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaMusicJsonTest {
    private val hub: (String) -> String? = { "HUB<$it>" }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    // ---- rooms ----

    @Test
    fun templateIsTheContractsText() {
        val t = HaMusicJson.ROOMS_TEMPLATE
        assertTrue(t.startsWith("{% set ns = namespace(r=[]) %}"))
        assertTrue(t.contains("integration_entities('music_assistant')"))
        assertTrue(t.contains("state_attr(e, 'device_class') != 'tv'"))
        assertTrue(t.contains("| map('list') | list"))
        assertTrue(t.endsWith("{% endfor %}{{ ns.r | to_json }}"))
    }

    @Test
    fun parsesTheRoomsTemplate() {
        val rows = HaMusicJson.parseRooms(MusicFixtures.text("rooms_template.json"))
        assertEquals(4, rows.size) // the sensor row is dropped
        val k = rows[0]
        assertEquals("media_player.kitchen_echo", k.entityId)
        assertEquals("Kitchen Echo", k.name)
        assertEquals("Kitchen", k.area)
        assertEquals("player", k.type)
        assertEquals(listOf(listOf("music_assistant", "Zk3vQ-fictional-client-id")), k.ids)
        assertEquals("01JFICTIONALENTRY0000000000", k.entry)
        assertEquals(listOf("media_player.living_room_speaker", "media_player.study_speaker"), rows[1].members)
        assertEquals(2, rows[2].ids.size)
        assertEquals("group", rows[3].type)
    }

    @Test(expected = Exception::class)
    fun roomsThatArentAListThrow() {
        HaMusicJson.parseRooms("""{"error": "x"}""")
    }

    @Test
    fun buildsRoomsThisDeviceFirstWithLiveNamesAndAvailability() {
        val rows = HaMusicJson.parseRooms(MusicFixtures.text("rooms_template.json"))
        val states = mapOf(
            "media_player.study_speaker" to MusicFixtures.state("media_player.study_speaker", "unavailable"),
            "media_player.living_room_speaker" to MusicFixtures.state(
                "media_player.living_room_speaker", "playing",
                obj("""{"friendly_name": "Lounge", "group_members": ["media_player.living_room_speaker"]}"""),
            ),
        )
        val rooms = HaMusicJson.buildRooms(rows, states, SelfMatch("media_player.study_speaker", How.ID))
        assertEquals(
            listOf("media_player.study_speaker", "media_player.all_speakers", "media_player.kitchen_echo", "media_player.living_room_speaker"),
            rooms.map { it.entityId },
        )
        assertTrue(rooms[0].isThisDevice)
        assertFalse(rooms[0].available)
        assertNull(rooms[0].area)
        assertTrue(rooms[1].isGroup)
        assertEquals("Lounge", rooms[3].name)
        assertEquals(listOf("media_player.living_room_speaker"), rooms[3].groupMembers)
        assertEquals("Kitchen", rooms[2].area)
        assertTrue(rooms[2].available) // no state yet counts as available
    }

    @Test
    fun roomsFromStatesWhenTheTemplateIsRefused() {
        val rows = HaMusicJson.roomRowsFromStates(listOf(
            MusicFixtures.state("media_player.kitchen_echo", "idle", obj("""{"friendly_name": "Kitchen Echo", "mass_player_type": "player"}""")),
            MusicFixtures.state("media_player.tv", "idle", obj("""{"mass_player_type": "player", "device_class": "tv"}""")),
            MusicFixtures.state("media_player.cast", "idle", obj("""{"friendly_name": "Cast"}""")),
        ))
        assertEquals(listOf("media_player.kitchen_echo"), rows.map { it.entityId })
        assertNull(rows[0].entry)
    }

    // ---- player ----

    @Test
    fun playerStateFromAttributes() {
        val s = MusicFixtures.state("media_player.kitchen_echo", "playing", MusicFixtures.json("player_state.json").jsonObject, 5_000L)
        val p = HaMusicJson.playerState(s)
        assertEquals("Lantern Song", p.title)
        assertEquals("The Copper Lanterns", p.artist)
        assertEquals("Harbour Lights", p.album)
        assertEquals("/api/media_player_proxy/media_player.kitchen_echo?token=fictional&cache=2", p.artUrl)
        assertTrue(p.playing)
        assertEquals(214.0, p.durationS!!, 0.0)
        assertEquals(61.5, p.positionS!!, 0.0)
        assertEquals(java.time.Instant.parse("2026-11-07T10:00:00.250Z").toEpochMilli(), p.positionAtMs)
        assertTrue(p.shuffle)
        assertEquals(Repeat.ALL, p.repeat)
        assertEquals(0.35f, p.volume!!, 0.0001f)
        assertFalse(p.muted)
        assertEquals(
            setOf(Control.PLAY, Control.PAUSE, Control.NEXT, Control.PREVIOUS, Control.SEEK, Control.SHUFFLE,
                Control.REPEAT, Control.VOLUME, Control.MUTE),
            p.can,
        )
        assertEquals(71.5, p.positionNow(p.positionAtMs + 10_000)!!, 0.001)
    }

    @Test
    fun playerStateDefaultsAndUnavailable() {
        val p = HaMusicJson.playerState(MusicFixtures.state("media_player.x", "unavailable",
            obj("""{"supported_features": 1048575, "entity_picture": "/api/x"}"""), 7_000L))
        assertFalse(p.playing)
        assertNull(p.title)
        assertNull(p.durationS)
        assertNull(p.volume)
        assertEquals("/api/x", p.artUrl)
        assertEquals(7_000L, p.positionAtMs)
        assertEquals(Repeat.OFF, p.repeat)
        assertTrue(p.can.isEmpty())

        val grouping = HaMusicJson.playerState(MusicFixtures.state("media_player.x", "idle",
            obj("""{"supported_features": ${HaMusicJson.F_GROUPING + HaMusicJson.F_PAUSE}}""")))
        assertEquals(setOf(Control.GROUP, Control.PAUSE), grouping.can)
    }

    // ---- items, library, search, queue ----

    @Test
    fun parsesALibraryPage() {
        val page = HaMusicJson.parseLibrary(MusicFixtures.json("library_playlists.json"), 0, 5, hub)
        assertEquals(3, page.items.size) // unknown type and missing uri dropped
        val first = page.items[0]
        assertEquals(MediaItem("library://playlist/12", MediaKind.PLAYLIST, "Sunday Kitchen", "Mira Fennwick",
            "HUB<http://192.168.1.20:8095/imageproxy/abc123?size=0>", favorite = true), first)
        assertEquals("HUB<https://images.example-cdn.test/a/rainy.jpg>", page.items[1].imageUrl)
        assertNull(page.items[2].imageUrl)
        assertTrue(page.hasMore) // 5 raw items for a limit of 5
        assertEquals(0, page.offset)

        val short = HaMusicJson.parseLibrary(MusicFixtures.json("library_playlists.json"), 0, 50, hub)
        assertFalse(short.hasMore)
        assertEquals(LibraryPage(emptyList(), 50, false), HaMusicJson.parseLibrary(null, 50, 50, hub))
    }

    @Test
    fun noImageWhenNotLinked() {
        val page = HaMusicJson.parseLibrary(MusicFixtures.json("library_playlists.json"), 0, 50) {
            HaMusicJson.hubImageUrl("", false, it)
        }
        assertTrue(page.items.all { it.imageUrl == null })
    }

    @Test
    fun parsesSearchResultsByKind() {
        val all = MediaKind.entries.toSet()
        val r = HaMusicJson.parseSearch(MusicFixtures.json("search.json"), all, hub)
        assertEquals(MediaKind.entries.toList(), r.keys.toList())
        assertEquals("The Copper Lanterns", r[MediaKind.ARTIST]!!.single().name)
        assertEquals("", r[MediaKind.ARTIST]!!.single().subtitle)
        val album = r[MediaKind.ALBUM]!!.single()
        assertEquals("Harbour Lights (Deluxe)", album.name)
        assertEquals("The Copper Lanterns", album.subtitle)
        val track = r[MediaKind.TRACK]!!.single()
        assertEquals("The Copper Lanterns, Ada Brightwater · Harbour Lights", track.subtitle)
        assertEquals("HUB<https://radio.example.test/logo.png>", r[MediaKind.RADIO]!!.single().imageUrl)
        assertTrue(r[MediaKind.PLAYLIST]!!.isEmpty())

        val some = HaMusicJson.parseSearch(MusicFixtures.json("search.json"), setOf(MediaKind.TRACK, MediaKind.ALBUM), hub)
        assertEquals(listOf(MediaKind.TRACK, MediaKind.ALBUM), some.keys.toList())
    }

    @Test
    fun parsesTheQueue() {
        val q = HaMusicJson.parseQueue(MusicFixtures.json("queue.json"), "media_player.kitchen_echo", hub)!!
        assertEquals(42, q.count)
        assertEquals("library://track/301", q.current!!.uri)
        assertEquals("HUB<http://192.168.1.20:8095/imageproxy/t301>", q.current!!.imageUrl)
        assertEquals(MediaItem("q-bbb", MediaKind.TRACK, "Harbour FM", "Morning Show", null), q.next)
        assertNull(HaMusicJson.parseQueue(JsonObject(emptyMap()), "media_player.kitchen_echo", hub))
        assertNull(HaMusicJson.parseQueue(null, "media_player.kitchen_echo", hub))
        assertTrue(HaMusicJson.isNoQueue(Exception("No active queue found for media_player.tv")))
        assertFalse(HaMusicJson.isNoQueue(Exception("timeout")))
    }

    // ---- request data ----

    @Test
    fun libraryRequest() {
        val c = HaMusicJson.libraryCall("E1", MediaKind.PLAYLIST, LibraryOrder.RECENT, favoritesOnly = false, offset = 50, limit = 50)
        assertEquals("music_assistant" to "get_library", c.domain to c.service)
        assertEquals(obj("""{"config_entry_id":"E1","media_type":"playlist","order_by":"last_played_desc","offset":50,"limit":50}"""), c.data)
        val fav = HaMusicJson.libraryCall("E1", MediaKind.ALBUM, LibraryOrder.NAME, favoritesOnly = true, offset = 0, limit = 10)
        assertEquals(obj("""{"config_entry_id":"E1","media_type":"album","order_by":"sort_name","favorite":true,"offset":0,"limit":10}"""), fav.data)
        assertEquals("timestamp_added_desc", HaMusicJson.orderBy(LibraryOrder.ADDED))
        assertEquals("play_count_desc", HaMusicJson.orderBy(LibraryOrder.MOST_PLAYED))
        assertEquals("random", HaMusicJson.orderBy(LibraryOrder.RANDOM))
    }

    @Test
    fun searchRequest() {
        val c = HaMusicJson.searchCall("E1", "copper", setOf(MediaKind.TRACK, MediaKind.ARTIST), 10)
        assertEquals("music_assistant" to "search", c.domain to c.service)
        assertEquals(obj("""{"config_entry_id":"E1","name":"copper","media_type":["track","artist"],"limit":10}"""), c.data)
    }

    @Test
    fun playAndTransferAndGroupRequests() {
        val item = MediaItem("library://playlist/12", MediaKind.PLAYLIST, "Sunday Kitchen", "", null)
        assertEquals(
            obj("""{"entity_id":"media_player.k","media_id":"library://playlist/12","media_type":"playlist","enqueue":"next","radio_mode":false}"""),
            HaMusicJson.playCall(item, "media_player.k", Enqueue.NEXT, false).data,
        )
        assertEquals(listOf("play", "next", "add", "replace"), Enqueue.entries.map { HaMusicJson.enqueue(it) })
        val t = HaMusicJson.transferCall("media_player.a", "media_player.b")
        assertEquals("music_assistant" to "transfer_queue", t.domain to t.service)
        assertEquals(obj("""{"entity_id":"media_player.b","source_player":"media_player.a","auto_play":true}"""), t.data)
        val j = HaMusicJson.joinCall("media_player.a", listOf("media_player.a", "media_player.b", "media_player.b"))
        assertEquals("media_player" to "join", j.domain to j.service)
        assertEquals(obj("""{"entity_id":"media_player.a","group_members":["media_player.b"]}"""), j.data)
        assertEquals(obj("""{"entity_id":"media_player.a"}"""), HaMusicJson.unjoinCall("media_player.a").data)
        assertEquals("unjoin", HaMusicJson.unjoinCall("media_player.a").service)
    }

    @Test
    fun controlRequests() {
        fun c(control: Control, value: Any? = null) = HaMusicJson.controlCall("media_player.k", control, value)
        assertEquals("media_play", c(Control.PLAY).service)
        assertEquals("media_pause", c(Control.PAUSE).service)
        assertEquals("media_next_track", c(Control.NEXT).service)
        assertEquals("media_previous_track", c(Control.PREVIOUS).service)
        assertEquals(obj("""{"entity_id":"media_player.k"}"""), c(Control.PLAY).data)
        assertEquals(obj("""{"entity_id":"media_player.k","seek_position":90.5}"""), c(Control.SEEK, 90.5).data)
        assertEquals(obj("""{"entity_id":"media_player.k","shuffle":true}"""), c(Control.SHUFFLE, true).data)
        assertEquals(obj("""{"entity_id":"media_player.k","repeat":"one"}"""), c(Control.REPEAT, Repeat.ONE).data)
        assertEquals("repeat_set", c(Control.REPEAT, "all").service)
        assertEquals(obj("""{"entity_id":"media_player.k","volume_level":1.0}"""), c(Control.VOLUME, 1.4f).data)
        assertEquals("volume_set", c(Control.VOLUME, 0.5).service)
        assertEquals(obj("""{"entity_id":"media_player.k","is_volume_muted":true}"""), c(Control.MUTE, true).data)
        assertEquals("join", c(Control.GROUP, listOf("media_player.b")).service)
        for ((control, bad) in listOf(Control.SEEK to null, Control.SHUFFLE to "yes", Control.REPEAT to "twice",
                Control.VOLUME to "loud", Control.MUTE to 1, Control.GROUP to "media_player.b")) {
            try {
                c(control, bad)
                throw AssertionError("$control accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ---- C8 ----

    @Test
    fun hubImageUrls() {
        assertEquals(
            "https://odysseus.example.test/api/chat/voice/v1/music/image?u=http%3A%2F%2F192.168.1.20%3A8095%2Fimageproxy%2Fabc%3Fsize%3D0&size=256",
            HaMusicJson.hubImageUrl("https://odysseus.example.test/api/chat/voice/v1/", true, "http://192.168.1.20:8095/imageproxy/abc?size=0"),
        )
        assertEquals(
            "http://hub.test/v1/music/image?u=https%3A%2F%2Fcdn.test%2Fa%20b.jpg&size=512",
            HaMusicJson.hubImageUrl("http://hub.test/v1", true, "https://cdn.test/a b.jpg", 9999),
        )
        assertNull(HaMusicJson.hubImageUrl("https://hub.test/v1", false, "https://cdn.test/a.jpg"))
        assertNull(HaMusicJson.hubImageUrl("", true, "https://cdn.test/a.jpg"))
        assertNull(HaMusicJson.hubImageUrl("https://hub.test/v1", true, null))
        assertNull(HaMusicJson.hubImageUrl("https://hub.test/v1", true, " "))
    }

}
