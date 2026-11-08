package com.msp1974.vacompanion.odysseus.home.players

import com.msp1974.vacompanion.odysseus.ha.HaException
import com.msp1974.vacompanion.odysseus.music.FakeHaApi
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OtherPlayersRulesTest {
    private val now = 1_000_000.0

    private fun player(
        id: String,
        state: String = "playing",
        area: String = "",
        title: String = "Song $id",
        artist: String = "Band",
        changed: Double = now - 60,
        ma: Boolean = false,
        features: Long = 0,
    ) = HaMediaPlayer(
        entityId = id, name = id.removePrefix("media_player."), state = state, area = area,
        title = title, artist = artist, changedAt = changed, musicAssistant = ma, features = features,
    )

    private fun snap(vararg p: HaMediaPlayer) = HaMediaSnapshot(now, p.toList())

    @Test
    fun parsesTheTemplateAnswer() {
        val json = """{"now": 1000000.5, "players": [
            {"id": "media_player.kitchen", "name": "Kitchen", "state": "playing", "area": "Kitchen",
             "title": "Fictional Song", "artist": "Demo Band", "album": "Demo", "picture": "/api/media_player_proxy/media_player.kitchen?token=x",
             "duration": 200, "position": 12.5, "position_at": 999990.5, "features": 4127295,
             "shuffle": true, "repeat": "all", "changed": 999000, "ma": true},
            {"id": "media_player.tv", "name": "TV", "state": "paused", "area": null, "title": null,
             "artist": null, "album": null, "picture": null, "duration": null, "position": null,
             "position_at": null, "features": 0, "shuffle": null, "repeat": null, "changed": 999500, "ma": false},
            {"id": "light.not_a_player", "name": "x", "state": "playing"}
        ]}"""
        val s = OtherPlayersRules.parse(Json.parseToJsonElement(json))!!
        assertEquals(1000000.5, s.haNow, 0.0)
        assertEquals(2, s.players.size)
        val k = s.players[0]
        assertEquals("Kitchen", k.area)
        assertEquals("Fictional Song", k.title)
        assertEquals(200.0, k.durationSec, 0.0)
        assertEquals(4127295L, k.features)
        assertTrue(k.shuffle)
        assertEquals("all", k.repeat)
        assertTrue(k.musicAssistant)
        val tv = s.players[1]
        assertEquals("", tv.title)
        assertNull(tv.picture)
        assertNull(tv.positionSec)
        assertEquals("off", tv.repeat)
    }

    /** The answer a real Home Assistant (2026.2) gave to PLAYERS_TEMPLATE for fictional players. */
    @Test
    fun parsesARealHomeAssistantAnswer() {
        val json = """{"now": 1794017529.342714, "players": [
            {"album": "Demo Album", "area": null, "artist": "Demo Band", "changed": 1794017524.052082,
             "duration": 215, "features": 4127295, "id": "media_player.kitchen", "ma": true,
             "name": "Kitchen speaker", "picture": "/api/media_player_proxy/media_player.kitchen?token=abc&cache=1",
             "position": 42, "position_at": 1794017524.0, "repeat": "all", "shuffle": false,
             "state": "playing", "title": "Fictional Song"},
            {"album": null, "area": null, "artist": null, "changed": 1794017524.058815, "duration": null,
             "features": 16437, "id": "media_player.office", "ma": false, "name": "Office", "picture": null,
             "position": null, "position_at": null, "repeat": null, "shuffle": null, "state": "paused",
             "title": "Another Song"},
            {"album": null, "area": null, "artist": null, "changed": 1794017524.073189, "duration": null,
             "features": 0, "id": "media_player.bare", "ma": false, "name": "bare", "picture": null,
             "position": null, "position_at": null, "repeat": null, "shuffle": null, "state": "playing",
             "title": null}
        ]}"""
        val s = OtherPlayersRules.parse(Json.parseToJsonElement(json))!!
        assertEquals(listOf("media_player.kitchen", "media_player.office"), OtherPlayersRules.candidates(s, "").map { it.entityId })
        val kitchen = OtherPlayersRules.pick(s, "", "")!!
        val np = OtherPlayersRules.toNowPlaying(kitchen, s.haNow, 0L, "http://ha.local:8123")
        assertEquals(47_342L, np.positionMs)
        assertTrue(setOf("play", "pause", "next", "previous", "shuffle", "repeat_all").all { it in np.commands })
        assertEquals(setOf("play", "pause", "previous", "next"), OtherPlayersRules.commands(s.players[1]))
    }

    @Test
    fun parseRejectsOtherShapes() {
        assertNull(OtherPlayersRules.parse(Json.parseToJsonElement("[]")))
        assertNull(OtherPlayersRules.parse(Json.parseToJsonElement("""{"players": []}""")))
        assertNull(OtherPlayersRules.parse(null))
    }

    @Test
    fun thisRoomComesFirstThenPlayingThenMostRecent() {
        val s = snap(
            player("media_player.office", changed = now - 10),
            player("media_player.kitchen", area = "Kitchen", changed = now - 500),
            player("media_player.bedroom", area = "Bedroom", changed = now - 5),
        )
        assertEquals("media_player.kitchen", OtherPlayersRules.pick(s, "kitchen", "")?.entityId)
        // No room known, or none playing there: the latest to change.
        assertEquals("media_player.bedroom", OtherPlayersRules.pick(s, "", "")?.entityId)
        assertEquals("media_player.bedroom", OtherPlayersRules.pick(s, "Garage", "")?.entityId)
    }

    @Test
    fun playingBeatsPausedAndOldPausesAreDropped() {
        val s = snap(
            player("media_player.a", state = "paused", changed = now - 5),
            player("media_player.b", state = "playing", changed = now - 600),
            player("media_player.c", state = "paused", changed = now - OtherPlayersRules.PAUSED_KEEP_SEC - 1),
        )
        val list = OtherPlayersRules.candidates(s, "")
        assertEquals(listOf("media_player.b", "media_player.a"), list.map { it.entityId })
    }

    @Test
    fun noTrackNoCandidate() {
        val s = snap(player("media_player.a", title = "", artist = ""), player("media_player.b", state = "idle"))
        assertNull(OtherPlayersRules.pick(s, "", ""))
    }

    @Test
    fun theSameTrackOnSeveralEntitiesIsListedOncePreferringMusicAssistant() {
        val s = snap(
            player("media_player.sonos_native", title = "Same", changed = now - 1),
            player("media_player.sonos_ma", title = "Same", changed = now - 2, ma = true),
        )
        val list = OtherPlayersRules.candidates(s, "")
        assertEquals(listOf("media_player.sonos_ma"), list.map { it.entityId })
    }

    @Test
    fun aChosenPlayerIsFollowedEvenWhenAnotherIsBetter() {
        val s = snap(
            player("media_player.kitchen", area = "Kitchen"),
            player("media_player.tv", state = "paused", changed = now - 5),
        )
        assertEquals("media_player.tv", OtherPlayersRules.pick(s, "Kitchen", "media_player.tv")?.entityId)
        // Chosen but not playing anything: nothing (not another speaker).
        assertNull(OtherPlayersRules.pick(s, "Kitchen", "media_player.garage"))
    }

    @Test
    fun aChosenPlayerPausedLongAgoIsNotShown() {
        val s = snap(player("media_player.tv", state = "paused", changed = now - OtherPlayersRules.PAUSED_KEEP_SEC - 1))
        assertNull(OtherPlayersRules.pick(s, "", "media_player.tv"))
        val idle = snap(player("media_player.tv", state = "idle"))
        assertNull(OtherPlayersRules.pick(idle, "", "media_player.tv"))
    }

    @Test
    fun commandsFollowSupportedFeatures() {
        val r = OtherPlayersRules
        assertEquals(emptySet<String>(), r.commands(player("media_player.a")))
        val all = r.FEATURE_PLAY or r.FEATURE_PAUSE or r.FEATURE_NEXT or r.FEATURE_PREVIOUS or r.FEATURE_SHUFFLE or r.FEATURE_REPEAT
        assertEquals(
            setOf("play", "pause", "next", "previous", "shuffle", "unshuffle", "repeat_off", "repeat_all", "repeat_one"),
            r.commands(player("media_player.a", features = all)),
        )
        assertEquals(setOf("pause", "next"), r.commands(player("media_player.a", features = r.FEATURE_PAUSE or r.FEATURE_NEXT)))
    }

    @Test
    fun nowPlayingMovesThePositionOnToHaNow() {
        val p = player("media_player.a", features = OtherPlayersRules.FEATURE_PAUSE).copy(
            durationSec = 200.0, positionSec = 10.0, positionAt = now - 5, picture = "/api/media_player_proxy/media_player.a?token=t",
        )
        val np = OtherPlayersRules.toNowPlaying(p, now, nowMs = 42L, haBase = "http://ha.local:8123/")
        assertEquals(15_000L, np.positionMs)
        assertEquals(42L, np.positionAtMs)
        assertEquals(200_000L, np.durationMs)
        assertTrue(np.active)
        assertTrue(np.playing)
        assertEquals(1000, np.speed)
        assertEquals("http://ha.local:8123/api/media_player_proxy/media_player.a?token=t", np.artworkUrl)

        val paused = OtherPlayersRules.toNowPlaying(p.copy(state = "paused"), now, 42L, "http://ha")
        assertEquals(10_000L, paused.positionMs)
        assertTrue(paused.active)
        assertFalse(paused.playing)

        val past = OtherPlayersRules.toNowPlaying(p.copy(positionSec = 199.0), now, 42L, "http://ha")
        assertEquals(200_000L, past.positionMs)
    }

    @Test
    fun pictureUrls() {
        val r = OtherPlayersRules
        assertNull(r.pictureUrl(null, "http://ha"))
        assertNull(r.pictureUrl("", "http://ha"))
        assertNull(r.pictureUrl("javascript:x", "http://ha"))
        assertEquals("https://cdn.example/a.jpg", r.pictureUrl("https://cdn.example/a.jpg", "http://ha"))
        assertEquals("http://ha/api/x", r.pictureUrl("/api/x", "http://ha/"))
    }

    @Test
    fun settleKeepsTheSameStateWhenOnlyThePositionMovedAsExpected() {
        val first = SendspinNowPlaying(title = "A", active = true, playing = true, speed = 1000, positionMs = 10_000, positionAtMs = 1_000)
        val onTime = first.copy(positionMs = 14_000, positionAtMs = 5_000)
        assertSame(first, OtherPlayersRules.settle(first, onTime, 5_000))
        val seeked = first.copy(positionMs = 60_000, positionAtMs = 5_000)
        assertSame(seeked, OtherPlayersRules.settle(first, seeked, 5_000))
        val paused = onTime.copy(playing = false, speed = 0)
        assertSame(paused, OtherPlayersRules.settle(first, paused, 5_000))
        assertSame(onTime, OtherPlayersRules.settle(null, onTime, 5_000))
    }

    @Test
    fun readsTheSnapshotThroughTheTemplate() = runBlocking {
        val ha = FakeHaApi()
        ha.templateAnswer = {
            """{"now": 5.0, "players": [{"id": "media_player.kitchen", "name": "Kitchen", "state": "playing", "title": "T"}]}"""
        }
        val s = OtherPlayersRules.snapshot(ha)
        assertEquals(listOf("media_player.kitchen"), s.players.map { it.entityId })
        val template = ha.templates.single()
        assertTrue(template.contains("states.media_player"))
        assertTrue(template.contains("area_name(s.entity_id)"))
    }

    @Test
    fun anOddAnswerIsAnError() = runBlocking {
        for (answer in listOf("not json", "[]")) {
            val ha = FakeHaApi().apply { templateAnswer = { answer } }
            try {
                OtherPlayersRules.snapshot(ha)
                fail("expected an error for $answer")
            } catch (_: HaException) {
            }
        }
    }

    @Test
    fun commandCalls() {
        val r = OtherPlayersRules
        for ((command, service) in listOf(
            "play" to "media_play", "pause" to "media_pause",
            "next" to "media_next_track", "previous" to "media_previous_track",
        )) {
            val call = r.commandCall("media_player.a", command)!!
            assertEquals("media_player", call.domain)
            assertEquals(service, call.service)
            assertEquals(JsonObject(mapOf("entity_id" to JsonPrimitive("media_player.a"))), call.data)
        }
        val shuffle = r.commandCall("media_player.a", "unshuffle")!!
        assertEquals("shuffle_set", shuffle.service)
        assertEquals(JsonPrimitive(false), shuffle.data["shuffle"])
        assertEquals(JsonPrimitive(true), r.commandCall("media_player.a", "shuffle")!!.data["shuffle"])
        assertEquals(JsonPrimitive("media_player.a"), shuffle.data["entity_id"])
        for (mode in listOf("off", "all", "one")) {
            val repeat = r.commandCall("media_player.a", "repeat_$mode")!!
            assertEquals("repeat_set", repeat.service)
            assertEquals(JsonPrimitive(mode), repeat.data["repeat"])
        }
        assertNull(r.commandCall("media_player.a", "seek"))
        assertNull(r.commandCall("light.kitchen", "play"))
    }

    @Test
    fun theEchosOwnMusicWins() {
        val r = OtherPlayersRules
        val other = SendspinNowPlaying(title = "Other", active = true, playing = true)
        val nothing = SendspinNowPlaying()
        val ownPlaying = SendspinNowPlaying(title = "Own", active = true, playing = true)
        val ownPaused = SendspinNowPlaying(title = "Own", active = false, playing = false)
        assertTrue(r.showOther(nothing, other))
        assertFalse(r.showOther(ownPlaying, other))
        // Own paused: another speaker that plays takes over; a paused one doesn't.
        assertTrue(r.showOther(ownPaused, other))
        assertFalse(r.showOther(ownPaused, other.copy(playing = false)))
        assertFalse(r.showOther(nothing, null))
    }
}
