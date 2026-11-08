package com.msp1974.vacompanion.odysseus.music

import com.msp1974.vacompanion.odysseus.ha.HaException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class HaMusicBackendTest {
    private val ha = FakeHaApi().apply { templateAnswer = { MusicFixtures.text("rooms_template.json") } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = AtomicLong(1_000_000L)
    @Volatile private var locked = false
    @Volatile private var clientId: String? = "Zk3vQ-fictional-client-id"

    private fun backend(refreshMs: Long = 60_000L) = HaMusicBackend(
        ha = ha,
        selfClientId = { clientId },
        area = { "Kitchen" },
        uuid = { "0f1e2d3c-fict-ion0-al00-uuid00000001" },
        isLocked = { locked },
        imageUrl = { "HUB<$it>" },
        scope = scope,
        clock = { now.get() },
        roomsRefreshMs = refreshMs,
    )

    @After
    fun tearDown() = scope.cancel()

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        withTimeout(5_000) {
            while (!check()) kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun roomsAreLiveWhileCollected() = runBlocking {
        val b = backend()
        assertTrue(b.rooms.value.isEmpty())
        assertTrue(ha.templates.isEmpty())
        val job = scope.launch { b.rooms.collect { } }
        val rooms = withTimeout(5_000) { b.rooms.first { it.isNotEmpty() } }
        assertEquals("media_player.kitchen_echo", rooms.first().entityId)
        assertTrue(rooms.first().isThisDevice)
        assertEquals(SelfMatch("media_player.kitchen_echo", How.ID), b.self.value)
        assertEquals(HaMusicJson.ROOMS_TEMPLATE, ha.templates.first())
        waitFor("watch") { ha.watched.isNotEmpty() }
        assertEquals(rooms.map { it.entityId }.toSet(), ha.watched.first())

        // a state change renames a room and marks another unavailable
        ha.live.value = mapOf(
            "media_player.kitchen_echo" to MusicFixtures.named("media_player.kitchen_echo", "Kitchen Echo"),
            "media_player.living_room_speaker" to MusicFixtures.named("media_player.living_room_speaker", "Lounge"),
            "media_player.study_speaker" to MusicFixtures.named("media_player.study_speaker", "Study", "unavailable"),
            "media_player.all_speakers" to MusicFixtures.named("media_player.all_speakers", "All Speakers"),
        )
        waitFor("rename") { b.rooms.value.any { it.name == "Lounge" } }
        assertFalse(b.rooms.value.first { it.entityId == "media_player.study_speaker" }.available)
        job.cancel()
    }

    @Test
    fun aNewOrRemovedEntityRunsTheTemplateAgain() = runBlocking {
        val b = backend()
        val job = scope.launch { b.rooms.collect { } }
        waitFor("watch") { ha.watched.isNotEmpty() }
        assertEquals(1, ha.templates.size)

        // HA drops the study speaker (removed from MA): the template runs again, once the gap is over
        ha.live.value = mapOf(
            "media_player.kitchen_echo" to MusicFixtures.named("media_player.kitchen_echo", "Kitchen Echo"),
            "media_player.living_room_speaker" to MusicFixtures.named("media_player.living_room_speaker", "Living"),
            "media_player.all_speakers" to MusicFixtures.named("media_player.all_speakers", "All"),
        )
        Thread.sleep(200)
        assertEquals(1, ha.templates.size) // within RELOAD_MIN_GAP_MS of the last run
        now.addAndGet(HaMusicBackend.RELOAD_MIN_GAP_MS)
        ha.templateAnswer = {
            """[{"entity_id": "media_player.kitchen_echo", "name": "Kitchen Echo", "area": "Kitchen", "type": "player",
                "members": [], "ids": [["music_assistant", "Zk3vQ-fictional-client-id"]], "entry": "E2"}]"""
        }
        ha.live.value = ha.live.value + ("media_player.kitchen_echo" to MusicFixtures.named("media_player.kitchen_echo", "Kitchen"))
        waitFor("reload") { ha.templates.size == 2 }
        waitFor("one room") { b.rooms.value.size == 1 }
        waitFor("re-watch") { ha.watched.size == 2 }
        assertEquals(setOf("media_player.kitchen_echo"), ha.watched.last())
        job.cancel()
    }

    @Test
    fun theTemplateRunsAgainEveryRefresh() = runBlocking {
        val b = backend(refreshMs = 50)
        val job = scope.launch { b.rooms.collect { } }
        waitFor("three runs") { ha.templates.size >= 3 }
        job.cancel()
    }

    @Test
    fun aFailedRefreshKeepsTheRoomsAndMarksOffline() = runBlocking {
        val b = backend()
        b.loadRooms()
        assertTrue(b.reachable.value)
        ha.templateAnswer = { throw HaException("connect timed out") }
        try {
            b.loadRooms()
            fail("expected a failure")
        } catch (_: HaException) {
        }
        val job = scope.launch { b.rooms.collect { } }
        waitFor("offline") { !b.reachable.value }
        assertEquals(4, b.rooms.value.size)
        job.cancel()
    }

    @Test
    fun anUnauthorizedTemplateFallsBackToStates() = runBlocking {
        ha.templateAnswer = { throw HaException("Unauthorized", 401) }
        ha.allStates = listOf(
            MusicFixtures.state("media_player.kitchen_echo", "idle",
                obj("""{"friendly_name": "VACA 0f1e2d3c-fict-ion0-al00-uuid00000001 Sendspin Player", "mass_player_type": "player"}""")),
            MusicFixtures.state("light.kitchen", "on"),
        )
        val rooms = backend().loadRooms()
        assertEquals(listOf("media_player.kitchen_echo"), rooms.map { it.entityId })
        assertTrue(rooms.single().isThisDevice) // by MA's default name
    }

    @Test
    fun aRefusedTemplateIsNotAskedAgainAndTheLibraryFailsAtOnce() = runBlocking {
        ha.templateAnswer = { throw HaException("Unauthorized", 401) }
        val b = backend()
        repeat(3) { b.loadRooms() }
        for (load in listOf<suspend () -> Unit>(
            { b.library(MediaKind.ALBUM, LibraryOrder.NAME, false, 0) },
            { b.search("lantern", setOf(MediaKind.TRACK)) },
            { b.detail(MediaItem("library://album/21", MediaKind.ALBUM, "Harbour Lights", "", null)) },
        )) {
            try {
                load(); fail()
            } catch (e: HaException) {
                assertEquals(HaMusicBackend.NEEDS_ADMIN, e.message)
            }
        }
        // One refusal at HA (each is a failed login there), no MA call without a config entry.
        assertEquals(1, ha.templates.size)
        assertTrue(ha.calls.isEmpty())

        // Asked again after TEMPLATE_RETRY_MS (e.g. the device signed in as an admin since).
        ha.templateAnswer = { MusicFixtures.text("rooms_template.json") }
        now.addAndGet(HaMusicBackend.TEMPLATE_RETRY_MS)
        b.loadRooms()
        assertEquals(2, ha.templates.size)
        b.library(MediaKind.ALBUM, LibraryOrder.NAME, false, 0)
        assertEquals(1, ha.calls.size)
    }

    @Test
    fun aNewKeyFallsBackToTheAreaGuess() = runBlocking {
        clientId = "a-new-key-id"
        val b = backend()
        b.loadRooms()
        assertEquals(SelfMatch("media_player.kitchen_echo", How.AREA), b.self.value)
        clientId = null
        b.loadRooms()
        assertEquals(How.AREA, b.self.value?.how)
    }

    @Test
    fun playerFollowsTheEntity() = runBlocking {
        val b = backend()
        ha.live.value = mapOf(
            "media_player.kitchen_echo" to MusicFixtures.state("media_player.kitchen_echo", "playing",
                MusicFixtures.json("player_state.json").jsonObject),
        )
        val p = withTimeout(5_000) { b.player("media_player.kitchen_echo").first() }
        assertEquals("Lantern Song", p.title)
        assertTrue(p.playing)
        assertEquals(setOf("media_player.kitchen_echo"), ha.watched.last())
    }

    @Test
    fun queueAsksWithReturnResponseAndMapsNoQueueToNull() = runBlocking {
        val b = backend()
        ha.answers["music_assistant.get_queue"] = { MusicFixtures.json("queue.json") }
        val q = b.queue("media_player.kitchen_echo")!!
        assertEquals(42, q.count)
        assertEquals(FakeHaApi.Call("music_assistant", "get_queue", obj("""{"entity_id":"media_player.kitchen_echo"}"""), true), ha.calls.last())

        ha.answers["music_assistant.get_queue"] = { throw HaException("No active queue found for media_player.tv") }
        assertNull(b.queue("media_player.tv"))

        ha.answers["music_assistant.get_queue"] = { throw HaException("boom") }
        try {
            b.queue("media_player.tv")
            fail("expected a failure")
        } catch (e: HaException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun libraryAndSearchUseTheConfigEntryFromTheTemplate() = runBlocking {
        val b = backend()
        ha.answers["music_assistant.get_library"] = { MusicFixtures.json("library_playlists.json") }
        val page = b.library(MediaKind.PLAYLIST, LibraryOrder.RECENT, favoritesOnly = true, offset = 0, limit = 5)
        assertEquals(1, ha.templates.size) // run once for the entry id
        assertEquals(3, page.items.size)
        assertEquals("HUB<http://192.168.1.20:8095/imageproxy/abc123?size=0>", page.items[0].imageUrl)
        assertEquals(
            FakeHaApi.Call("music_assistant", "get_library", obj(
                """{"config_entry_id":"01JFICTIONALENTRY0000000000","media_type":"playlist","order_by":"last_played_desc","favorite":true,"offset":0,"limit":5}""",
            ), true),
            ha.calls.last(),
        )

        ha.answers["music_assistant.search"] = { MusicFixtures.json("search.json") }
        val r = b.search("  lantern ", setOf(MediaKind.TRACK))
        assertEquals(1, ha.templates.size)
        assertEquals(listOf(MediaKind.TRACK), r.keys.toList())
        assertEquals(
            FakeHaApi.Call("music_assistant", "search", obj(
                """{"config_entry_id":"01JFICTIONALENTRY0000000000","name":"lantern","media_type":["track"],"limit":10}""",
            ), true),
            ha.calls.last(),
        )
        val before = ha.calls.size
        assertTrue(b.search("  ", setOf(MediaKind.TRACK)).isEmpty())
        assertTrue(b.search("x", emptySet()).isEmpty())
        assertEquals(before, ha.calls.size)
    }

    @Test
    fun libraryWithoutMusicAssistantThrows() = runBlocking {
        ha.templateAnswer = { "[]" }
        try {
            backend().library(MediaKind.ALBUM, LibraryOrder.NAME, false, 0)
            fail("expected a failure")
        } catch (e: HaException) {
            assertTrue(e.message!!.contains("Music Assistant"))
        }
    }

    @Test
    fun everyMediaPlayerIsWatchedAndOneTurnedOnIsListedAtOnce() = runBlocking {
        ha.templateAnswer = { MusicFixtures.text("rooms_template_all.json") }
        val b = backend()
        val job = scope.launch { b.rooms.collect { } }
        waitFor("rooms") { b.rooms.value.isNotEmpty() }
        assertEquals(
            listOf("media_player.kitchen_2", "media_player.living_room_tv", "media_player.study_speaker"),
            b.rooms.value.map { it.entityId },
        )
        // Watched: also the hidden twin and the Cast that is off.
        waitFor("watch") { ha.watched.isNotEmpty() }
        assertEquals(
            setOf("media_player.kitchen_2", "media_player.kitchen", "media_player.living_room_tv",
                "media_player.bedroom_cast", "media_player.study_speaker"),
            ha.watched.first(),
        )
        // The Cast is turned on: listed at the next state, without another template run.
        ha.live.value = mapOf(
            "media_player.kitchen_2" to MusicFixtures.state("media_player.kitchen_2", "playing",
                kotlinx.serialization.json.JsonObject(MusicFixtures.json("player_state.json").jsonObject +
                    ("friendly_name" to kotlinx.serialization.json.JsonPrimitive("Kitchen"))), 4_000L),
            "media_player.kitchen" to MusicFixtures.named("media_player.kitchen", "Kitchen", "playing"),
            "media_player.living_room_tv" to MusicFixtures.named("media_player.living_room_tv", "Living Room TV", "off"),
            "media_player.bedroom_cast" to MusicFixtures.named("media_player.bedroom_cast", "Bedroom Cast", "idle"),
            "media_player.study_speaker" to MusicFixtures.named("media_player.study_speaker", "Study Speaker", "unavailable"),
        )
        waitFor("cast") { b.rooms.value.any { it.entityId == "media_player.bedroom_cast" } }
        assertEquals(
            listOf("media_player.bedroom_cast", "media_player.kitchen_2", "media_player.study_speaker"),
            b.rooms.value.map { it.entityId },
        )
        assertEquals(1, ha.templates.size)
        // The live states of the listed rooms, for the clock.
        val live = b.live.value
        assertEquals(setOf("media_player.bedroom_cast", "media_player.kitchen_2", "media_player.study_speaker"), live.keys)
        assertEquals("playing", live.getValue("media_player.kitchen_2").status)
        assertEquals("Lantern Song", live.getValue("media_player.kitchen_2").player.title)
        assertEquals(4_000L, live.getValue("media_player.kitchen_2").updatedMs)
        job.cancel()
    }

    @Test
    fun onlyMaPlayersCanBeThisDevice() = runBlocking {
        // A plain player with MA's default name for this device, in this device's area: never claimed.
        ha.templateAnswer = {
            """[{"entity_id": "media_player.vaca_cast", "name": "VACA 0f1e2d3c-fict-ion0-al00-uuid00000001 Sendspin Player",
                 "area": "Kitchen", "state": "idle", "ma": false, "type": "player", "members": [], "ids": [["music_assistant", "Zk3vQ-fictional-client-id"]], "entry": null}]"""
        }
        val b = backend()
        val rooms = b.loadRooms()
        assertEquals(listOf("media_player.vaca_cast"), rooms.map { it.entityId })
        assertNull(b.self.value)
        assertFalse(rooms.single().isThisDevice)
    }

    @Test
    fun anUnauthorizedTemplateListsEveryMediaPlayerFromStates() = runBlocking {
        ha.templateAnswer = { throw HaException("Unauthorized", 401) }
        ha.allStates = listOf(
            MusicFixtures.state("media_player.ma", "idle", obj("""{"friendly_name": "Den", "mass_player_type": "player"}""")),
            MusicFixtures.state("media_player.cast", "playing", obj("""{"friendly_name": "Cast"}""")),
            MusicFixtures.state("media_player.tv", "off", obj("""{"friendly_name": "TV"}""")),
        )
        val rooms = backend().loadRooms()
        assertEquals(listOf("media_player.cast" to false, "media_player.ma" to true), rooms.map { it.entityId to it.musicAssistant })
    }

    @Test
    fun aPlainPlayerHasNoQueueAndRefusesMusicAssistantsActions() = runBlocking {
        ha.templateAnswer = { MusicFixtures.text("rooms_template_all.json") }
        val b = backend()
        b.loadRooms()
        assertNull(b.queue("media_player.living_room_tv"))
        assertTrue(ha.calls.isEmpty())
        val item = MediaItem("library://track/301", MediaKind.TRACK, "Lantern Song", "", null)
        val refused: List<suspend () -> Unit> = listOf(
            { b.play(item, "media_player.living_room_tv", Enqueue.PLAY) },
            { b.transfer("media_player.kitchen_2", "media_player.living_room_tv") },
            { b.transfer("media_player.living_room_tv", "media_player.kitchen_2") },
            { b.group("media_player.kitchen_2", listOf("media_player.living_room_tv")) },
            { b.group("media_player.living_room_tv", listOf("media_player.kitchen_2")) },
            { b.ungroup("media_player.living_room_tv") },
        )
        for (a in refused) {
            try {
                a(); fail("expected a refusal")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("isn't a Music Assistant player"))
            }
        }
        assertTrue(ha.calls.isEmpty())
        // Its own controls go to Home Assistant as for any player.
        b.control("media_player.living_room_tv", Control.PAUSE)
        b.control("media_player.living_room_tv", Control.VOLUME, 0.5)
        assertEquals(listOf("media_pause", "volume_set"), ha.calls.map { it.service })
        // An MA room still queues and plays.
        ha.answers["music_assistant.get_queue"] = { MusicFixtures.json("queue.json") }
        b.queue("media_player.kitchen_2")
        b.play(item, "media_player.kitchen_2", Enqueue.PLAY)
        assertEquals(listOf("get_queue", "play_media"), ha.calls.drop(2).map { it.service })
    }

    @Test
    fun actionsSendTheirRequests() = runBlocking {
        val b = backend()
        val item = MediaItem("library://album/21", MediaKind.ALBUM, "Harbour Lights", "", null)
        b.play(item, "media_player.kitchen_echo", Enqueue.REPLACE, radio = true)
        assertEquals(FakeHaApi.Call("music_assistant", "play_media", obj(
            """{"entity_id":"media_player.kitchen_echo","media_id":"library://album/21","media_type":"album","enqueue":"replace","radio_mode":true}""",
        ), false), ha.calls.last())

        b.control("media_player.kitchen_echo", Control.VOLUME, 0.25)
        assertEquals(FakeHaApi.Call("media_player", "volume_set",
            obj("""{"entity_id":"media_player.kitchen_echo","volume_level":0.25}"""), false), ha.calls.last())

        b.transfer("media_player.living_room_speaker", "media_player.kitchen_echo")
        assertEquals(FakeHaApi.Call("music_assistant", "transfer_queue", obj(
            """{"entity_id":"media_player.kitchen_echo","source_player":"media_player.living_room_speaker","auto_play":true}""",
        ), false), ha.calls.last())

        b.group("media_player.kitchen_echo", listOf("media_player.study_speaker"))
        assertEquals(FakeHaApi.Call("media_player", "join", obj(
            """{"entity_id":"media_player.kitchen_echo","group_members":["media_player.study_speaker"]}""",
        ), false), ha.calls.last())

        b.ungroup("media_player.study_speaker")
        assertEquals(FakeHaApi.Call("media_player", "unjoin", obj("""{"entity_id":"media_player.study_speaker"}"""), false), ha.calls.last())

        // no-ops: moving to the same room, grouping with nobody
        val n = ha.calls.size
        b.transfer("media_player.kitchen_echo", "media_player.kitchen_echo")
        b.group("media_player.kitchen_echo", listOf("media_player.kitchen_echo"))
        assertEquals(n, ha.calls.size)
    }

    @Test
    fun everyActionIsRefusedWhileLocked() = runBlocking {
        val b = backend()
        locked = true
        val item = MediaItem("library://track/301", MediaKind.TRACK, "Lantern Song", "", null)
        val actions: List<suspend () -> Unit> = listOf(
            { b.play(item, "media_player.kitchen_echo", Enqueue.PLAY) },
            { b.control("media_player.kitchen_echo", Control.PAUSE) },
            { b.transfer("media_player.a", "media_player.b") },
            { b.group("media_player.a", listOf("media_player.b")) },
            { b.ungroup("media_player.a") },
        )
        for (a in actions) {
            try {
                a()
                fail("expected MusicLocked")
            } catch (_: MusicLocked) {
            }
        }
        assertTrue(ha.calls.isEmpty())
        // reading still works
        ha.answers["music_assistant.get_queue"] = { MusicFixtures.json("queue.json") }
        assertEquals(42, b.queue("media_player.kitchen_echo")!!.count)
    }
}
