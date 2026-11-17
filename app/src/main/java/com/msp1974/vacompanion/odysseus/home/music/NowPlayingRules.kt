package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the pure rules of the Music page's Now playing tab (docs/odysseus/music.md, "Now
// playing"): which player state to draw, the queue lines, the room labels, the room "Move here"
// takes music from. Unit-tested (NowPlayingRulesTest).

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.QueueSummary
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch

/** What the Now playing tab draws for the selected room. */
sealed interface NowPlayingView {
    /** Music Assistant's player of the room (through Home Assistant); controls go to MusicActions. */
    data class Ma(val state: PlayerState) : NowPlayingView

    /** This device's Sendspin player (MA's state isn't there); controls go out as Sendspin commands. */
    data class Sendspin(val state: PlayerState) : NowPlayingView

    /** Nothing plays; [state] is the room's idle state (for its volume) or null. */
    data class Nothing(val state: PlayerState?) : NowPlayingView
}

object NowPlayingRules {
    /** True when [state] has a track to show (a title, or at least an artist). */
    fun hasTrack(state: PlayerState?): Boolean =
        state != null && (!state.title.isNullOrBlank() || !state.artist.isNullOrBlank())

    /**
     * The view for [roomId]: MA's state while it has a track; else, for this device's own room
     * ([selfRoomId]), Sendspin's state ([sendspin], SendspinNowPlaying.toPlayerState(), passed only
     * while Sendspin is active) while it has a track; else nothing playing.
     */
    fun view(roomId: String?, ma: PlayerState?, selfRoomId: String?, sendspin: PlayerState?): NowPlayingView = when {
        hasTrack(ma) -> NowPlayingView.Ma(ma!!)
        roomId != null && roomId == selfRoomId && hasTrack(sendspin) -> NowPlayingView.Sendspin(sendspin!!)
        else -> NowPlayingView.Nothing(ma)
    }

    /** "Next: <title> — <artists>", or null when the queue has no next item. */
    fun nextLine(queue: QueueSummary?): String? = queue?.next?.let { "Next: " + itemText(it) }

    /** "N in the queue", or null for no queue or an empty one. */
    fun countLine(queue: QueueSummary?): String? = queue?.count?.takeIf { it > 0 }?.let { "$it in the queue" }

    /** Whether the queue block shows at all. */
    fun showQueue(queue: QueueSummary?): Boolean = nextLine(queue) != null || countLine(queue) != null

    /** An item as "<name> — <subtitle>" (the subtitle left out when blank). */
    fun itemText(item: MediaItem): String =
        listOf(item.name, item.subtitle).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" — ")

    /**
     * The key the queue is fetched again on: the room and what plays (title, artist). The queue
     * summary is asked for when this changes.
     */
    fun queueKey(roomId: String?, state: PlayerState?): String =
        listOf(roomId.orEmpty(), state?.title.orEmpty(), state?.artist.orEmpty()).joinToString("\u0000")

    /** The mark for this device's room: "This Echo", or "This Echo?" when only guessed from the area. */
    fun selfMark(room: Room, self: SelfMatch?): String? = when {
        self == null || self.entityId != room.entityId -> null
        self.how == How.AREA -> "This Echo?"
        else -> "This Echo"
    }

    /** A room's name in the picker, with [selfMark] after it. */
    fun roomLabel(room: Room, self: SelfMatch?): String =
        selfMark(room, self)?.let { "${room.name} · $it" } ?: room.name

    /** The name of [roomId] among [rooms], or "this room" when it isn't one. */
    fun roomName(roomId: String?, rooms: List<Room>): String =
        rooms.firstOrNull { it.entityId == roomId }?.name ?: "this room"

    /** The rooms in the picker: this device's room first, then the others in their order. */
    fun pickerRooms(rooms: List<Room>, self: SelfMatch?): List<Room> {
        val own = rooms.filter { it.entityId == self?.entityId }
        return own + rooms.filter { it.entityId != self?.entityId }
    }

    /**
     * The room "Move here" takes music from: the first available Music Assistant room other than
     * [roomId] that plays ([playingIds]) and isn't a group containing [roomId]; null for none (no
     * button), and when [roomId] itself is unavailable or not a Music Assistant room.
     */
    fun moveFrom(roomId: String?, rooms: List<Room>, playingIds: Set<String>): String? {
        if (roomId == null || rooms.any { it.entityId == roomId && (!it.available || !it.musicAssistant) }) return null
        return rooms.firstOrNull {
            it.entityId != roomId && it.available && it.musicAssistant && it.entityId in playingIds && roomId !in it.groupMembers
        }?.entityId
    }

    /** The repeat mode after [repeat] when the repeat button is tapped: off → all → one → off. */
    fun nextRepeat(repeat: Repeat): Repeat = when (repeat) {
        Repeat.OFF -> Repeat.ALL
        Repeat.ALL -> Repeat.ONE
        Repeat.ONE -> Repeat.OFF
    }

    /** Whether [state] takes [control] (the tab shows only what the player can do). */
    fun can(state: PlayerState?, control: Control): Boolean = state != null && control in state.can
}
