package com.msp1974.vacompanion.odysseus.home.music

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsRulesTest {
    private fun room(
        id: String, name: String = id, area: String? = null, self: Boolean = false,
        group: Boolean = false, members: List<String> = emptyList(), available: Boolean = true,
    ) = Room("media_player.$id", name, area, self, group, members.map { "media_player.$it" }, available)

    private fun state(
        title: String? = null, artist: String? = null, playing: Boolean = false,
        volume: Float? = 0.4f, can: Set<Control> = setOf(Control.PLAY, Control.PAUSE, Control.VOLUME, Control.GROUP),
    ) = PlayerState(
        title, artist, null, null, null, playing, null, null, 0L, false, Repeat.OFF, volume, false, can,
    )

    @Test
    fun statusTexts() {
        val kitchen = room("kitchen")
        assertEquals(RoomStatus("Song — Band", true), RoomsRules.status(kitchen, state("Song", "Band", playing = true)))
        assertEquals(RoomStatus("Song — Band · Paused", false), RoomsRules.status(kitchen, state("Song", "Band")))
        assertEquals(RoomStatus("Song", true), RoomsRules.status(kitchen, state("Song", " ", playing = true)))
        assertEquals(RoomStatus("Idle", false), RoomsRules.status(kitchen, state()))
        assertEquals(RoomStatus("Off", false), RoomsRules.status(kitchen, state(volume = null, can = emptySet())))
        assertEquals(RoomStatus("", false), RoomsRules.status(kitchen, null))
        assertEquals(RoomStatus("Unavailable", false), RoomsRules.status(room("x", available = false), state("Song", playing = true)))
    }

    @Test
    fun badgeAndArea() {
        val echo = room("echo", "Echo", area = "Kitchen")
        assertEquals("This Echo", RoomsRules.badge(echo, SelfMatch(echo.entityId, How.ID)))
        assertEquals("This Echo?", RoomsRules.badge(echo, SelfMatch(echo.entityId, How.AREA)))
        assertNull(RoomsRules.badge(echo, SelfMatch("media_player.other", How.ID)))
        assertEquals("This Echo", RoomsRules.badge(room("e", self = true), null))
        assertEquals("Kitchen", RoomsRules.areaLine(echo))
        assertNull(RoomsRules.areaLine(room("k", "Kitchen", area = "kitchen")))
        assertNull(RoomsRules.areaLine(room("k", "Kitchen", area = " ")))
    }

    @Test
    fun groups() {
        val leader = room("a", members = listOf("a", "b", "c"))
        val member = room("b", members = listOf("a", "b", "c"))
        val alone = room("d")
        val maGroup = room("g", group = true, members = listOf("a", "b"))
        assertTrue(RoomsRules.isLeader(leader))
        assertFalse(RoomsRules.isMember(leader))
        assertTrue(RoomsRules.isMember(member))
        assertEquals("+2 rooms", RoomsRules.groupLabel(leader))
        assertEquals("+1 room", RoomsRules.groupLabel(room("a", members = listOf("a", "b"))))
        assertNull(RoomsRules.groupLabel(member))
        assertNull(RoomsRules.groupLabel(alone))
        assertNull(RoomsRules.groupLabel(room("a", members = listOf("a"))))
        assertEquals("Group of 2", RoomsRules.groupLabel(maGroup))
        assertTrue(RoomsRules.sameGroup(leader, member))
        assertFalse(RoomsRules.sameGroup(leader, alone))
    }

    @Test
    fun transferSources() {
        val target = room("echo")
        val playing = room("kitchen")
        val paused = room("office")
        val off = room("garage", available = false)
        val leader = room("a", members = listOf("a", "b"))
        val member = room("b", members = listOf("a", "b"))
        val rooms = listOf(target, playing, paused, off, leader, member)
        val states = mapOf(
            target.entityId to state("T", playing = true),
            playing.entityId to state("S", playing = true),
            paused.entityId to state("S"),
            off.entityId to state("S", playing = true),
            leader.entityId to state("S", playing = true),
            member.entityId to state("S", playing = true),
        )
        assertEquals(listOf(playing, leader), RoomsRules.transferSources(target, rooms, states))
        // A room in the target's group isn't a source.
        assertEquals(listOf(target, playing), RoomsRules.transferSources(member, rooms, states))
        assertEquals(emptyList<Room>(), RoomsRules.transferSources(target, listOf(target, playing), emptyMap()))
        // Nothing moves to an unavailable room.
        assertEquals(emptyList<Room>(), RoomsRules.transferSources(off, rooms, states))
    }

    @Test
    fun groupCandidates() {
        val a = room("a")
        val b = room("b")
        val noGroup = room("c")
        val gone = room("d", available = false)
        val maGroup = room("g", group = true, members = listOf("a"))
        val rooms = listOf(a, b, noGroup, gone, maGroup)
        val states = mapOf(
            a.entityId to state(), b.entityId to state(),
            noGroup.entityId to state(can = setOf(Control.PLAY)),
            gone.entityId to state(), maGroup.entityId to state(),
        )
        assertEquals(listOf(b), RoomsRules.groupCandidates(a, rooms, states))
        assertEquals(emptyList<Room>(), RoomsRules.groupCandidates(noGroup, rooms, states))
        assertEquals(emptyList<Room>(), RoomsRules.groupCandidates(a, rooms, states - b.entityId))
    }

    /** Review: an MA group player containing the target isn't a source (as NowPlayingRules.moveFrom). */
    @Test
    fun maGroupContainingTargetIsNoTransferSource() {
        val kitchen = room("kitchen")
        val g = room("downstairs", group = true, members = listOf("kitchen", "living"))
        val rooms = listOf(kitchen, g)
        val states = mapOf(kitchen.entityId to state(), g.entityId to state("S", playing = true))
        assertEquals(emptyList<Room>(), RoomsRules.transferSources(kitchen, rooms, states))
        assertNull(NowPlayingRules.moveFrom(kitchen.entityId, rooms, setOf(g.entityId)))
        // Nor is a member room a source for the MA group player that contains it.
        val playingKitchen = states + (kitchen.entityId to state("S", playing = true))
        assertEquals(emptyList<Room>(), RoomsRules.transferSources(g, rooms, playingKitchen))
    }

    /** Review: a member of another room's group offers no "Group with…" (it would act on its leader). */
    @Test
    fun memberCannotOpenGroupDialog() {
        val leader = room("a", members = listOf("a", "b"))
        val member = room("b", members = listOf("a", "b"))
        val other = room("c")
        val rooms = listOf(leader, member, other)
        val states = mapOf(
            leader.entityId to state(playing = true), member.entityId to state(playing = true),
            other.entityId to state(),
        )
        assertTrue(RoomsRules.canGroup(member, states[member.entityId]))
        assertEquals(emptyList<Room>(), RoomsRules.groupCandidates(member, rooms, states))
        // The leader still can, with its member shown (ticked).
        assertEquals(listOf(member, other), RoomsRules.groupCandidates(leader, rooms, states))
    }

    @Test
    fun groupChanges() {
        assertEquals(listOf("b", "c") to emptyList<String>(), RoomsRules.groupChanges("a", emptyList(), setOf("b", "c")))
        assertEquals(emptyList<String>() to listOf("c"), RoomsRules.groupChanges("a", listOf("a", "b", "c"), setOf("b")))
        assertEquals(emptyList<String>() to emptyList<String>(), RoomsRules.groupChanges("a", listOf("a", "b"), setOf("b")))
        val (join, leave) = RoomsRules.groupChanges("a", listOf("a", "b"), setOf("c"))
        assertEquals(listOf("c"), join)
        assertEquals(listOf("b"), leave)
    }

    @Test
    fun columnsByWidth() {
        assertEquals(1, RoomsRules.columns(700f))
        assertEquals(1, RoomsRules.columns(839f))
        assertEquals(2, RoomsRules.columns(840f))
        assertEquals(2, RoomsRules.columns(1199f))
        assertEquals(3, RoomsRules.columns(1200f))
    }

    @Test
    fun playPause() {
        assertEquals(Control.PAUSE, RoomsRules.playPauseControl(state(playing = true)))
        assertEquals(Control.PLAY, RoomsRules.playPauseControl(state()))
        assertNull(RoomsRules.playPauseControl(state(can = emptySet())))
        assertNull(RoomsRules.playPauseControl(null))
    }
}
