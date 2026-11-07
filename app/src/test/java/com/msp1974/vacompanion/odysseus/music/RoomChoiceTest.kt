package com.msp1974.vacompanion.odysseus.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomChoiceTest {
    private fun room(id: String, area: String?) = Room("media_player.$id", id, area, false, false, emptyList(), true)

    private val rooms = listOf(room("a", null), room("b", "Kitchen"), room("c", "Kitchen"), room("d", "Study"))

    @Test
    fun thisDevicesRoomFirst() {
        assertEquals("media_player.d", RoomChoice.defaultRoom(rooms, SelfMatch("media_player.d", How.ID), "Kitchen", "media_player.a"))
    }

    @Test
    fun thenTheFirstRoomInTheArea() {
        assertEquals("media_player.b", RoomChoice.defaultRoom(rooms, null, "kitchen", "media_player.a"))
        assertEquals("media_player.b", RoomChoice.defaultRoom(rooms, SelfMatch("media_player.gone", How.ID), "Kitchen", null))
    }

    @Test
    fun thenTheLastRoomUsedThenTheFirst() {
        assertEquals("media_player.d", RoomChoice.defaultRoom(rooms, null, "Garage", "media_player.d"))
        assertEquals("media_player.a", RoomChoice.defaultRoom(rooms, null, null, "media_player.gone"))
        assertNull(RoomChoice.defaultRoom(emptyList(), null, "Kitchen", "media_player.a"))
    }

    @Test
    fun aSelectionIsKeptWhileItIsARoom() {
        assertEquals("media_player.c", RoomChoice.keepOrDefault("media_player.c", rooms, SelfMatch("media_player.d", How.ID), null, null))
        assertEquals("media_player.d", RoomChoice.keepOrDefault("media_player.x", rooms, SelfMatch("media_player.d", How.ID), null, null))
    }
}
