package com.msp1974.vacompanion.odysseus.home.players

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.HaMusicJson
import com.msp1974.vacompanion.odysseus.music.LivePlayer
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherPlayersRulesTest {
    private val now = 1_793_000_000_000L
    private val minute = 60_000L

    private fun room(id: String, area: String? = null, ma: Boolean = false) =
        Room(id, id.removePrefix("media_player."), area, isThisDevice = false, isGroup = false,
            groupMembers = emptyList(), available = true, musicAssistant = ma)

    private fun live(
        status: String = "playing",
        title: String? = "Song",
        artist: String? = "Band",
        updated: Long = now - minute,
        can: Set<Control> = emptySet(),
    ) = LivePlayer(
        status,
        PlayerState(title = title, artist = artist, album = null, artUrl = null, playing = status == "playing",
            durationS = 200.0, positionS = 12.0, positionAtMs = now - 2_000, shuffle = false, repeat = Repeat.OFF,
            volume = null, muted = false, can = can),
        updated,
    )

    private fun speaker(id: String, area: String? = null, ma: Boolean = false, l: LivePlayer = live(title = "Song $id"), pausedSince: Long? = null) =
        Speaker(room(id, area, ma), l, pausedSince ?: if (l.status == "paused") l.updatedMs else null)

    @Test
    fun speakersComeFromTheSharedListAndItsLiveStates() {
        val rooms = listOf(room("media_player.kitchen"), room("media_player.tv"))
        val l = mapOf("media_player.kitchen" to live(), "media_player.gone" to live())
        val s = OtherPlayersRules.speakers(rooms, l, emptyMap())
        assertEquals(listOf("media_player.kitchen"), s.map { it.entityId }) // no state yet: not a speaker
    }

    @Test
    fun thisRoomComesFirstThenPlayingThenMaThenMostRecent() {
        val list = listOf(
            speaker("media_player.a", l = live(title = "A", updated = now - 5 * minute)),
            speaker("media_player.b", l = live(title = "B", updated = now - minute)),
            speaker("media_player.c", area = "Kitchen", l = live("paused", title = "C")),
            speaker("media_player.d", ma = true, l = live(title = "D", updated = now - 10 * minute)),
        )
        val order = OtherPlayersRules.candidates(list, " kitchen ", now).map { it.entityId }
        assertEquals(listOf("media_player.c", "media_player.d", "media_player.b", "media_player.a"), order)
    }

    @Test
    fun bufferingCountsAsPlayingAndOldPausesAreDropped() {
        val buffering = speaker("media_player.a", l = live("buffering", title = "A"))
        val oldPause = speaker("media_player.b", l = live("paused", title = "B"), pausedSince = now - 16 * minute)
        val idle = speaker("media_player.c", l = live("idle", title = "C"))
        val noTrack = speaker("media_player.d", l = live(title = null, artist = " "))
        assertEquals(listOf("media_player.a"), OtherPlayersRules.candidates(listOf(buffering, oldPause, idle, noTrack), "", now).map { it.entityId })
    }

    @Test
    fun theSameTrackOnSeveralPlayersIsListedOncePreferringMusicAssistant() {
        val plain = speaker("media_player.sonos", l = live(title = "Same", updated = now))
        val ma = speaker("media_player.sonos_ma", ma = true, l = live(title = "Same", updated = now - minute))
        assertEquals(listOf("media_player.sonos_ma"), OtherPlayersRules.candidates(listOf(plain, ma), "", now).map { it.entityId })
    }

    @Test
    fun aChosenPlayerIsFollowedOnlyWhileShowable() {
        val best = speaker("media_player.a", area = "Kitchen")
        val chosen = speaker("media_player.b", l = live("paused", title = "B"), pausedSince = now - 5 * minute)
        val r = OtherPlayersRules
        assertEquals("media_player.b", r.pick(listOf(best, chosen), "Kitchen", "media_player.b", now)?.entityId)
        assertEquals("media_player.a", r.pick(listOf(best, chosen), "Kitchen", "", now)?.entityId)
        assertNull(r.pick(listOf(best, chosen), "Kitchen", "media_player.b", now + 11 * minute))
        assertNull(r.pick(listOf(best), "Kitchen", "media_player.unknown", now))
    }

    @Test
    fun pausedSinceIsKeptWhileItStaysPaused() {
        val r = OtherPlayersRules
        val first = r.pausedSince(emptyMap(), mapOf("a" to live("paused", updated = 100), "b" to live("playing")))
        assertEquals(mapOf("a" to 100L), first)
        // A later volume change moves last_updated: the pause time stays.
        assertEquals(mapOf("a" to 100L), r.pausedSince(first, mapOf("a" to live("paused", updated = 900))))
        // Played again, then paused again: a new pause time.
        val playing = r.pausedSince(first, mapOf("a" to live("playing", updated = 1_000)))
        assertEquals(emptyMap<String, Long>(), playing)
        assertEquals(mapOf("a" to 2_000L), r.pausedSince(playing, mapOf("a" to live("paused", updated = 2_000))))
    }

    @Test
    fun commandsFollowTheControls() {
        val r = OtherPlayersRules
        assertEquals(emptySet<String>(), r.commands(emptySet()))
        assertEquals(setOf("play", "pause", "next", "previous"),
            r.commands(setOf(Control.PLAY, Control.PAUSE, Control.NEXT, Control.PREVIOUS, Control.VOLUME, Control.SEEK)))
        assertEquals(setOf("shuffle", "unshuffle", "repeat_off", "repeat_all", "repeat_one"),
            r.commands(setOf(Control.SHUFFLE, Control.REPEAT)))
    }

    @Test
    fun commandsBecomeControls() {
        val r = OtherPlayersRules
        assertEquals(Control.PLAY to null, r.control("play"))
        assertEquals(Control.PAUSE to null, r.control("pause"))
        assertEquals(Control.NEXT to null, r.control("next"))
        assertEquals(Control.PREVIOUS to null, r.control("previous"))
        assertEquals(Control.SHUFFLE to true, r.control("shuffle"))
        assertEquals(Control.SHUFFLE to false, r.control("unshuffle"))
        assertEquals(Control.REPEAT to Repeat.ALL, r.control("repeat_all"))
        assertNull(r.control("seek"))
        // Each one is a media_player call the backend can make.
        for (c in listOf("play", "pause", "next", "previous", "shuffle", "unshuffle", "repeat_off", "repeat_all", "repeat_one")) {
            val (control, value) = r.control(c)!!
            assertEquals("media_player", HaMusicJson.controlCall("media_player.a", control, value).domain)
        }
    }

    @Test
    fun nowPlayingMovesToSendspinsClockAtThatOneBoundary() {
        val offset = now - 50_000L // wall minus elapsedRealtime
        val l = live(can = setOf(Control.PLAY, Control.PAUSE)).let {
            it.copy(player = it.player.copy(artUrl = "/api/media_player_proxy/media_player.a?token=x", shuffle = true, repeat = Repeat.ONE))
        }
        val np = OtherPlayersRules.toNowPlaying(l, "http://ha:8123", offset)
        assertEquals("Song", np.title)
        assertEquals("http://ha:8123/api/media_player_proxy/media_player.a?token=x", np.artworkUrl)
        assertEquals(200_000L, np.durationMs)
        assertEquals(12_000L, np.positionMs)
        assertEquals(48_000L, np.positionAtMs) // now - 2 s on the wall clock
        assertEquals(14_000L, np.positionNow(50_000L)) // 2 s later on elapsedRealtime
        assertTrue(np.active && np.playing)
        assertEquals(setOf("play", "pause"), np.commands)
        assertTrue(np.shuffle)
        assertEquals("one", np.repeat)
        val paused = OtherPlayersRules.toNowPlaying(live("paused"), "http://ha", offset)
        assertFalse(paused.playing)
        assertEquals(12_000L, paused.positionNow(99_999L))
        assertTrue(OtherPlayersRules.toNowPlaying(live("buffering"), "http://ha", offset).playing)
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
