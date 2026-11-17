package com.msp1974.vacompanion.odysseus.home.music

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.QueueSummary
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingRulesTest {
    private fun state(title: String? = "Blue Song", artist: String? = "The Fictionals", playing: Boolean = true) = PlayerState(
        title = title, artist = artist, album = null, artUrl = null, playing = playing,
        durationS = 200.0, positionS = 10.0, positionAtMs = 0L, shuffle = false, repeat = Repeat.OFF,
        volume = 0.4f, muted = false, can = setOf(Control.PLAY, Control.PAUSE),
    )

    private fun room(id: String, name: String, available: Boolean = true, members: List<String> = emptyList()) =
        Room(id, name, area = null, isThisDevice = false, isGroup = members.isNotEmpty(), groupMembers = members, available = available)

    private fun item(name: String, subtitle: String = "") = MediaItem("uri:$name", MediaKind.TRACK, name, subtitle, null)

    @Test
    fun maStateWithATrackWins() {
        val ma = state()
        assertEquals(NowPlayingView.Ma(ma), NowPlayingRules.view("kitchen", ma, "kitchen", state(title = "Other")))
    }

    @Test
    fun ownRoomFallsBackToSendspin() {
        val sendspin = state(title = "Pushed")
        assertEquals(NowPlayingView.Sendspin(sendspin), NowPlayingRules.view("kitchen", null, "kitchen", sendspin))
        val idle = state(title = null, artist = null, playing = false)
        assertEquals(NowPlayingView.Sendspin(sendspin), NowPlayingRules.view("kitchen", idle, "kitchen", sendspin))
    }

    @Test
    fun otherRoomNeverUsesSendspin() {
        assertEquals(NowPlayingView.Nothing(null), NowPlayingRules.view("lounge", null, "kitchen", state()))
    }

    @Test
    fun nothingKeepsTheIdleStateForVolume() {
        val idle = state(title = null, artist = null, playing = false)
        assertEquals(NowPlayingView.Nothing(idle), NowPlayingRules.view("kitchen", idle, "kitchen", null))
        assertEquals(NowPlayingView.Nothing(null), NowPlayingRules.view(null, null, null, null))
    }

    @Test
    fun queueLines() {
        val q = QueueSummary(item("Now"), item("Later Song", "Ann Example"), 12)
        assertEquals("Next: Later Song — Ann Example", NowPlayingRules.nextLine(q))
        assertEquals("12 in the queue", NowPlayingRules.countLine(q))
        assertTrue(NowPlayingRules.showQueue(q))
        assertEquals("Next: Radio One", NowPlayingRules.nextLine(QueueSummary(null, item("Radio One", " "), 0)))
    }

    @Test
    fun emptyOrMissingQueueIsHidden() {
        assertNull(NowPlayingRules.nextLine(null))
        assertNull(NowPlayingRules.countLine(QueueSummary(item("Now"), null, 0)))
        assertFalse(NowPlayingRules.showQueue(null))
        assertFalse(NowPlayingRules.showQueue(QueueSummary(item("Now"), null, 0)))
    }

    @Test
    fun queueKeyChangesWithRoomAndTrack() {
        val a = NowPlayingRules.queueKey("kitchen", state())
        assertEquals(a, NowPlayingRules.queueKey("kitchen", state(playing = false)))
        assertNotEquals(a, NowPlayingRules.queueKey("lounge", state()))
        assertNotEquals(a, NowPlayingRules.queueKey("kitchen", state(title = "Next one")))
    }

    @Test
    fun thisEchoMark() {
        val kitchen = room("kitchen", "Kitchen")
        assertEquals("Kitchen · This Echo", NowPlayingRules.roomLabel(kitchen, SelfMatch("kitchen", How.ID)))
        assertEquals("Kitchen · This Echo?", NowPlayingRules.roomLabel(kitchen, SelfMatch("kitchen", How.AREA)))
        assertEquals("Kitchen", NowPlayingRules.roomLabel(kitchen, SelfMatch("lounge", How.ID)))
        assertEquals("Kitchen", NowPlayingRules.roomLabel(kitchen, null))
    }

    @Test
    fun pickerPutsThisDeviceFirst() {
        val rooms = listOf(room("a", "A"), room("b", "B"), room("c", "C"))
        assertEquals(listOf("b", "a", "c"), NowPlayingRules.pickerRooms(rooms, SelfMatch("b", How.ID)).map { it.entityId })
        assertEquals(listOf("a", "b", "c"), NowPlayingRules.pickerRooms(rooms, null).map { it.entityId })
    }

    @Test
    fun moveFromSkipsThisRoomUnavailableAndOwnGroup() {
        val rooms = listOf(
            room("kitchen", "Kitchen"), room("group", "Downstairs", members = listOf("kitchen", "lounge")),
            room("gone", "Gone", available = false), room("lounge", "Lounge"),
        )
        assertEquals("lounge", NowPlayingRules.moveFrom("kitchen", rooms, setOf("kitchen", "group", "gone", "lounge")))
        assertNull(NowPlayingRules.moveFrom("kitchen", rooms, setOf("kitchen")))
        assertNull(NowPlayingRules.moveFrom(null, rooms, setOf("lounge")))
        // Nothing moves to an unavailable room.
        assertNull(NowPlayingRules.moveFrom("gone", rooms, setOf("lounge")))
    }

    @Test
    fun roomNameFallsBack() {
        assertEquals("Kitchen", NowPlayingRules.roomName("kitchen", listOf(room("kitchen", "Kitchen"))))
        assertEquals("this room", NowPlayingRules.roomName("x", emptyList()))
    }

    @Test
    fun repeatCycles() {
        assertEquals(Repeat.ALL, NowPlayingRules.nextRepeat(Repeat.OFF))
        assertEquals(Repeat.ONE, NowPlayingRules.nextRepeat(Repeat.ALL))
        assertEquals(Repeat.OFF, NowPlayingRules.nextRepeat(Repeat.ONE))
    }

    @Test
    fun hasTrackNeedsTitleOrArtist() {
        assertFalse(NowPlayingRules.hasTrack(null))
        assertFalse(NowPlayingRules.hasTrack(state(title = " ", artist = null)))
        assertTrue(NowPlayingRules.hasTrack(state(title = null, artist = "Someone")))
    }

    @Test
    fun moveHereIsMusicAssistantsOnly() {
        val tv = room("media_player.tv", "TV").copy(musicAssistant = false)
        val den = room("media_player.den", "Den")
        val office = room("media_player.office", "Office")
        // Not to a plain room, not from one.
        assertNull(NowPlayingRules.moveFrom(tv.entityId, listOf(tv, den), setOf(den.entityId)))
        assertNull(NowPlayingRules.moveFrom(den.entityId, listOf(tv, den), setOf(tv.entityId)))
        assertEquals(office.entityId, NowPlayingRules.moveFrom(den.entityId, listOf(tv, den, office), setOf(tv.entityId, office.entityId)))
    }
}
