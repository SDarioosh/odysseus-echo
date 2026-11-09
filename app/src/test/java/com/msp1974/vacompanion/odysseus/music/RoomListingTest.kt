package com.msp1974.vacompanion.odysseus.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomListingTest {
    private fun row(id: String, ma: Boolean, state: String? = null) =
        RoomRow(id, id, "", if (ma) "player" else null, emptyList(), emptyList(), null, musicAssistant = ma, state = state)

    private fun room(id: String, name: String, area: String?, ma: Boolean) =
        Room(id, name, area, isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = true, musicAssistant = ma)

    @Test
    fun maPlayersAreAlwaysListedOthersOnlyWhileOn() {
        val r = RoomListing
        for (s in listOf("off", "standby", "unavailable", "unknown", null)) {
            assertTrue(r.listed(row("ma", ma = true), s))
            assertFalse("plain $s", r.listed(row("tv", ma = false), s))
        }
        for (s in listOf("playing", "paused", "idle", "on", "buffering")) {
            assertTrue("plain $s", r.listed(row("tv", ma = false), s))
        }
        // No live state yet: the template's.
        assertTrue(r.listed(row("tv", ma = false, state = "idle"), null))
        assertFalse(r.listed(row("tv", ma = false, state = "off"), null))
        // The live state wins.
        assertFalse(r.listed(row("tv", ma = false, state = "idle"), "off"))
    }

    @Test
    fun anEchosOwnVacaPlayerIsNeverListed() {
        val vaca = row("media_player.bedroom_echo_media_player", ma = false).copy(vaca = true, device = "Bedroom Echo")
        for (s in listOf("playing", "idle", "on", null)) assertFalse("vaca $s", RoomListing.listed(vaca, s))
    }

    @Test
    fun aPlainTwinOfAnMaPlayerIsHidden() {
        val ma = room("media_player.kitchen_2", "Kitchen", "Kitchen", ma = true)
        val twin = room("media_player.kitchen", "  kitchen ", "kitchen", ma = false)
        val spaced = room("media_player.kitchen_3", "KITCHEN", " Kitchen  ", ma = false)
        val otherArea = room("media_player.kitchen_b", "Kitchen", "Barn", ma = false)
        val other = room("media_player.tv", "Living Room TV", "Kitchen", ma = false)
        assertEquals(
            listOf(ma, otherArea, other),
            RoomListing.withoutTwins(listOf(ma, twin, spaced, otherArea, other)),
        )
    }

    @Test
    fun anAreaMissingOnEitherSideStillMatchesByName() {
        val maNoArea = room("media_player.kitchen_2", "Kitchen", null, ma = true)
        val plain = room("media_player.kitchen", "Kitchen", "Kitchen", ma = false)
        assertTrue(RoomListing.isTwin(plain, maNoArea))
        assertTrue(RoomListing.isTwin(plain.copy(area = null), maNoArea.copy(area = "Kitchen")))
        // Inner whitespace counts once.
        assertTrue(RoomListing.isTwin(plain.copy(name = "Living   room"), maNoArea.copy(name = "living room")))
        // Two MA players, or two plain ones, are never twins.
        assertFalse(RoomListing.isTwin(plain, plain))
        assertFalse(RoomListing.isTwin(maNoArea, maNoArea))
        // Without any MA player nothing is hidden.
        assertEquals(listOf(plain, plain.copy(entityId = "media_player.x")), RoomListing.withoutTwins(listOf(plain, plain.copy(entityId = "media_player.x"))))
    }
}
