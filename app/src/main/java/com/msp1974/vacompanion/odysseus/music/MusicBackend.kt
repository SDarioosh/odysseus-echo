package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: the Music page's domain (plan contract I2, docs/odysseus/music.md). The page talks
// to a MusicBackend, never to Home Assistant; HaMusicBackend is the one implementation today.
// PlayerState, Control and Repeat are in PlayerState.kt (shared with odysseus/player/).

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class MediaKind { TRACK, ALBUM, ARTIST, PLAYLIST, RADIO, PODCAST, AUDIOBOOK }
enum class Enqueue { PLAY, NEXT, ADD, REPLACE }          // MA: play, next, add, replace

data class MediaItem(val uri: String, val kind: MediaKind, val name: String,
                     val subtitle: String,                // artists, or album/owner
                     val imageUrl: String?, val favorite: Boolean = false)

data class Room(val entityId: String, val name: String, val area: String?,
                val isThisDevice: Boolean, val isGroup: Boolean,
                val groupMembers: List<String>, val available: Boolean,
                // Odysseus fork: a Music Assistant player (library, queue, play, move and group
                // work); false for any other media player (its own transport and volume only).
                val musicAssistant: Boolean = true)

data class QueueSummary(val current: MediaItem?, val next: MediaItem?, val count: Int)
data class LibraryPage(val items: List<MediaItem>, val offset: Int, val hasMore: Boolean)
enum class LibraryOrder { NAME, RECENT, ADDED, MOST_PLAYED, RANDOM }

interface MusicBackend {
    val rooms: StateFlow<List<Room>>                       // live while collected; every media player (Room.musicAssistant)
    fun player(entityId: String): Flow<PlayerState>       // live
    suspend fun queue(entityId: String): QueueSummary?     // null: not an MA queue (always for a non-MA room)
    suspend fun library(kind: MediaKind, order: LibraryOrder, favoritesOnly: Boolean,
                        offset: Int, limit: Int = 50): LibraryPage
    suspend fun search(text: String, kinds: Set<MediaKind>, limit: Int = 10): Map<MediaKind, List<MediaItem>>
    // play, transfer, group and ungroup take Music Assistant rooms only (IllegalArgumentException otherwise).
    suspend fun play(item: MediaItem, roomId: String, enqueue: Enqueue, radio: Boolean = false)
    suspend fun control(roomId: String, control: Control, value: Any? = null) // seek s, volume 0..1, …
    suspend fun transfer(fromRoomId: String, toRoomId: String)
    suspend fun group(leaderId: String, memberIds: List<String>)
    suspend fun ungroup(roomId: String)

    /**
     * Odysseus fork addition beyond contract I2 (E14): what an artist or album holds, for the
     * Library's detail view (MediaDetail.kt). Default: nothing listable (the view then offers only
     * the play menu for the item itself).
     */
    suspend fun detail(item: MediaItem): MediaDetail = MediaDetail()
}

/** Thrown by every play / control / transfer / group call while Lock mode is on (odysseus/lock/). */
class MusicLocked : Exception("Locked")
