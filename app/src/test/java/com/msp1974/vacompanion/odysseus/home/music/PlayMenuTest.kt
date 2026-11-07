package com.msp1974.vacompanion.odysseus.home.music

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.Enqueue
import com.msp1974.vacompanion.odysseus.music.LibraryOrder
import com.msp1974.vacompanion.odysseus.music.LibraryPage
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.MusicBackend
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.QueueSummary
import com.msp1974.vacompanion.odysseus.music.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records play() calls; everything else does nothing. */
private class RecordingBackend : MusicBackend {
    data class Play(val uri: String, val room: String, val enqueue: Enqueue, val radio: Boolean)
    val plays = mutableListOf<Play>()
    override val rooms: StateFlow<List<Room>> = MutableStateFlow(emptyList())
    override fun player(entityId: String): Flow<PlayerState> = emptyFlow()
    override suspend fun queue(entityId: String): QueueSummary? = null
    override suspend fun library(kind: MediaKind, order: LibraryOrder, favoritesOnly: Boolean, offset: Int, limit: Int) =
        LibraryPage(emptyList(), offset, false)
    override suspend fun search(text: String, kinds: Set<MediaKind>, limit: Int): Map<MediaKind, List<MediaItem>> = emptyMap()
    override suspend fun play(item: MediaItem, roomId: String, enqueue: Enqueue, radio: Boolean) {
        plays += Play(item.uri, roomId, enqueue, radio)
    }
    override suspend fun control(roomId: String, control: Control, value: Any?) = Unit
    override suspend fun transfer(fromRoomId: String, toRoomId: String) = Unit
    override suspend fun group(leaderId: String, memberIds: List<String>) = Unit
    override suspend fun ungroup(roomId: String) = Unit
}

class PlayMenuTest {
    private fun room(id: String, name: String, available: Boolean = true) =
        Room(id, name, null, isThisDevice = false, isGroup = false, groupMembers = emptyList(), available = available)

    private val kitchen = room("media_player.kitchen", "Kitchen")
    private val office = room("media_player.office", "Office")
    private val bedroom = room("media_player.bedroom", "Bedroom", available = false)
    private val all = listOf(office, bedroom, kitchen)

    @Test
    fun optionsByKind() {
        assertEquals(listOf(PlayOption.PLAY_NOW), PlayMenu.options(MediaKind.RADIO))
        assertEquals(PlayOption.entries.toList(), PlayMenu.options(MediaKind.TRACK))
        assertEquals(PlayOption.entries.toList(), PlayMenu.options(MediaKind.ARTIST))
        for (k in listOf(MediaKind.ALBUM, MediaKind.PLAYLIST, MediaKind.PODCAST, MediaKind.AUDIOBOOK)) {
            assertFalse(PlayOption.RADIO in PlayMenu.options(k))
            assertEquals(4, PlayMenu.options(k).size)
        }
    }

    @Test
    fun hereFirstThenTheOtherAvailableRooms() {
        val choices = PlayMenu.rooms(all, kitchen.entityId)
        assertEquals(listOf("Kitchen (here)", "Office"), choices.map { it.label })
        assertEquals(listOf("Office", "Kitchen"), PlayMenu.rooms(all, null).map { it.label })
    }

    @Test
    fun defaultIsTheSelectedRoomElseHere() {
        val choices = PlayMenu.rooms(all, kitchen.entityId)
        assertEquals(office, PlayMenu.defaultRoom(choices, office.entityId)?.room)
        assertEquals(kitchen, PlayMenu.defaultRoom(choices, bedroom.entityId)?.room)
        assertEquals(kitchen, PlayMenu.defaultRoom(choices, null)?.room)
        assertEquals(office, PlayMenu.defaultRoom(PlayMenu.rooms(all, null), null)?.room)
        assertNull(PlayMenu.defaultRoom(emptyList(), null))
    }

    @Test
    fun everyOptionAsksTheBackend() = runBlocking {
        val backend = RecordingBackend()
        val track = MediaItem("library://track/301", MediaKind.TRACK, "Lantern Song", "The Copper Lanterns", null)
        for (option in PlayOption.entries) PlayMenu.perform(backend, track, office.entityId, option)
        assertEquals(
            listOf(
                RecordingBackend.Play(track.uri, office.entityId, Enqueue.PLAY, false),
                RecordingBackend.Play(track.uri, office.entityId, Enqueue.NEXT, false),
                RecordingBackend.Play(track.uri, office.entityId, Enqueue.ADD, false),
                RecordingBackend.Play(track.uri, office.entityId, Enqueue.REPLACE, false),
                RecordingBackend.Play(track.uri, office.entityId, Enqueue.PLAY, true),
            ),
            backend.plays,
        )
    }

    @Test
    fun afterwards() {
        assertTrue(PlayMenu.showsPlaying(PlayOption.PLAY_NOW))
        assertTrue(PlayMenu.showsPlaying(PlayOption.REPLACE))
        assertTrue(PlayMenu.showsPlaying(PlayOption.RADIO))
        assertFalse(PlayMenu.showsPlaying(PlayOption.PLAY_NEXT))
        assertEquals("Plays next in Office", PlayMenu.doneText(PlayOption.PLAY_NEXT, "Office"))
        assertEquals("Added to the queue in Office", PlayMenu.doneText(PlayOption.ADD, "Office"))
        assertNull(PlayMenu.doneText(PlayOption.PLAY_NOW, "Office"))
    }
}
