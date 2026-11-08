package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: which of Home Assistant's media players the Music page and the clock's other
// speakers list (docs/odysseus/music.md, "Rooms"). Pure, unit-tested (RoomListingTest).

object RoomListing {
    /** A plain player in one of these states has nothing to control: not listed. */
    val HIDDEN_STATES = setOf("unavailable", "unknown", "off", "standby")

    /**
     * True when [row] is listed with its live [state] (HA's state text; null: not known yet, the
     * template's [RoomRow.state] then): a Music Assistant player always (as before), any other
     * media player while it is on.
     */
    fun listed(row: RoomRow, state: String?): Boolean {
        if (row.musicAssistant) return true
        val s = state ?: row.state ?: return false
        return s !in HIDDEN_STATES
    }

    /** A name or area for comparing: trimmed, inner whitespace collapsed, any case. */
    fun key(text: String?): String = text.orEmpty().trim().replace(WHITESPACE, " ").lowercase()

    private val WHITESPACE = Regex("\\s+")

    /**
     * True when [plain] (not an MA player) is the same speaker as the MA player [ma]: Music
     * Assistant often exposes a speaker Home Assistant also has through its own integration (a
     * Sonos as `media_player.kitchen` and MA's `media_player.kitchen_2`). Same name, and the same
     * area or one of them without an area (MA's devices often have none; nor does anything without
     * an admin token).
     */
    fun isTwin(plain: Room, ma: Room): Boolean {
        if (plain.musicAssistant || !ma.musicAssistant) return false
        if (key(plain.name) != key(ma.name)) return false
        val a = key(plain.area)
        val b = key(ma.area)
        return a == b || a.isEmpty() || b.isEmpty()
    }

    /** [rooms] without the plain players that are a twin of a listed MA player ([isTwin]). */
    fun withoutTwins(rooms: List<Room>): List<Room> {
        val ma = rooms.filter { it.musicAssistant }
        if (ma.isEmpty()) return rooms
        return rooms.filter { r -> r.musicAssistant || ma.none { isTwin(r, it) } }
    }
}
