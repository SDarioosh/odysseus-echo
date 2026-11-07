package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the pure rules of the Music page's Rooms tab (docs/odysseus/music.md, "Rooms"):
// the status line of a room, which rooms can move their music to a room, which rooms can be
// grouped with which, group labels and the grid's column count. Unit-tested (RoomsRulesTest).

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch

/** What a room's status line says, and whether it is greyed ([active] false). */
data class RoomStatus(val text: String, val active: Boolean)

object RoomsRules {
    const val UNAVAILABLE = "Unavailable"
    const val OFF = "Off"
    const val IDLE = "Idle"
    const val THIS_ECHO = "This Echo"
    const val THIS_ECHO_GUESS = "This Echo?"

    /** Width (dp) from which the tab shows a grid of cards; below it one column. */
    const val GRID_FROM_DP = 840
    private const val THREE_COLUMNS_FROM_DP = 1200

    /**
     * The status line of [room] with its [state] (null: not known yet). "Title — Artist" (with
     * " · Paused" while paused) when something is loaded; else "Unavailable" for a room Home
     * Assistant marks unavailable, "Off" for a player that takes no control at all (HA reports
     * every control off for an off or unknown player), "Idle" otherwise; "" before the first state.
     */
    fun status(room: Room, state: PlayerState?): RoomStatus {
        if (!room.available) return RoomStatus(UNAVAILABLE, false)
        if (state == null) return RoomStatus("", false)
        val title = state.title?.trim()?.takeIf { it.isNotEmpty() }
        val artist = state.artist?.trim()?.takeIf { it.isNotEmpty() }
        if (title == null && artist == null) {
            return RoomStatus(if (state.can.isEmpty() && state.volume == null) OFF else IDLE, false)
        }
        val what = listOfNotNull(title, artist).joinToString(" — ")
        return if (state.playing) RoomStatus(what, true) else RoomStatus("$what · Paused", false)
    }

    /** The badge beside [room]'s name: "This Echo", "This Echo?" for a guess from the area, else null. */
    fun badge(room: Room, self: SelfMatch?): String? = when {
        self != null && self.entityId == room.entityId -> if (self.how == How.AREA) THIS_ECHO_GUESS else THIS_ECHO
        room.isThisDevice -> THIS_ECHO
        else -> null
    }

    /** The area shown under a room's name: only when it isn't blank and differs from the name. */
    fun areaLine(room: Room): String? =
        room.area?.trim()?.takeIf { it.isNotEmpty() && !it.equals(room.name.trim(), ignoreCase = true) }

    /** The other rooms [room] is joined with (its group members without itself); empty when none. */
    fun groupedWith(room: Room): List<String> =
        if (room.entityId in room.groupMembers) room.groupMembers.filter { it != room.entityId }.distinct() else emptyList()

    /** True when [room] leads a group it formed (HA lists the leader first in group_members). */
    fun isLeader(room: Room): Boolean =
        !room.isGroup && groupedWith(room).isNotEmpty() && room.groupMembers.firstOrNull() == room.entityId

    /** True when [room] was joined to another room's group (it plays the leader's music). */
    fun isMember(room: Room): Boolean =
        !room.isGroup && groupedWith(room).isNotEmpty() && room.groupMembers.firstOrNull() != room.entityId

    /** "+N rooms" for a group leader, "Group of N" for a Music Assistant group player, else null. */
    fun groupLabel(room: Room): String? = when {
        room.isGroup && room.groupMembers.isNotEmpty() -> "Group of ${room.groupMembers.distinct().size}"
        isLeader(room) -> groupedWith(room).size.let { if (it == 1) "+1 room" else "+$it rooms" }
        else -> null
    }

    /** True when [a] and [b] play together (one's group lists the other). */
    fun sameGroup(a: Room, b: Room): Boolean =
        b.entityId in groupedWith(a) || a.entityId in groupedWith(b)

    /**
     * The rooms whose music "Move music here" can bring to [target]: available, playing, not
     * [target] itself, not grouped with it, and not a member of another room's group (its leader
     * is the one to move). None when [target] itself is unavailable. [states]: the rooms' player
     * states by entity id.
     */
    fun transferSources(target: Room, rooms: List<Room>, states: Map<String, PlayerState?>): List<Room> =
        if (!target.available) emptyList() else rooms.filter { r ->
            r.entityId != target.entityId && r.available && !isMember(r) && !sameGroup(r, target) &&
                states[r.entityId]?.playing == true
        }

    /** True when [room] can lead or join a group: available, a plain player, and its player takes GROUP. */
    fun canGroup(room: Room, state: PlayerState?): Boolean =
        room.available && !room.isGroup && state != null && Control.GROUP in state.can

    /**
     * The rooms [leader] can be grouped with: other rooms that can group ([canGroup]); none when
     * [leader] itself can't. Rooms already in its group are included (shown ticked).
     */
    fun groupCandidates(leader: Room, rooms: List<Room>, states: Map<String, PlayerState?>): List<Room> {
        if (!canGroup(leader, states[leader.entityId])) return emptyList()
        return rooms.filter { it.entityId != leader.entityId && canGroup(it, states[it.entityId]) }
    }

    /**
     * What to send when [leader]'s group is changed from [current] to [chosen] members: the members
     * to join (all of [chosen], or none when nothing new was ticked) and the members to take out.
     */
    fun groupChanges(leader: String, current: List<String>, chosen: Set<String>): Pair<List<String>, List<String>> {
        val now = current.filter { it != leader }.toSet()
        val wanted = chosen - leader
        val join = if ((wanted - now).isEmpty()) emptyList() else wanted.toList()
        val leave = (now - wanted).toList()
        return join to leave
    }

    /** Columns of the room list at [widthDp]: 1 below [GRID_FROM_DP], 2 below 1200 dp, else 3. */
    fun columns(widthDp: Float): Int = when {
        widthDp < GRID_FROM_DP -> 1
        widthDp < THREE_COLUMNS_FROM_DP -> 2
        else -> 3
    }

    /** The play/pause control the room's button sends, or null when the player takes neither. */
    fun playPauseControl(state: PlayerState?): Control? = when {
        state == null -> null
        state.playing && Control.PAUSE in state.can -> Control.PAUSE
        !state.playing && Control.PLAY in state.can -> Control.PLAY
        else -> null
    }
}
