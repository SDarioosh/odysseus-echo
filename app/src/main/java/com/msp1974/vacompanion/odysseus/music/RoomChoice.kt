package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: which room the Music page starts on (plan contract I3, last paragraph). Pure,
// unit-tested (RoomChoiceTest).

object RoomChoice {
    /**
     * The room picker's default: this device's room, else the first room in [area] (the device's
     * area, pushed by the VACA integration), else the room used last, else the first room. Null
     * when there are no rooms.
     */
    fun defaultRoom(rooms: List<Room>, self: SelfMatch?, area: String?, lastRoom: String?): String? {
        if (rooms.isEmpty()) return null
        self?.entityId?.takeIf { id -> rooms.any { it.entityId == id } }?.let { return it }
        if (!area.isNullOrBlank()) {
            rooms.firstOrNull { it.area?.trim()?.equals(area.trim(), ignoreCase = true) == true }?.let { return it.entityId }
        }
        lastRoom?.takeIf { id -> id.isNotBlank() && rooms.any { it.entityId == id } }?.let { return it }
        return rooms.first().entityId
    }

    /** [selected] while it is still a room, else [defaultRoom]. */
    fun keepOrDefault(selected: String?, rooms: List<Room>, self: SelfMatch?, area: String?, lastRoom: String?): String? =
        selected?.takeIf { id -> rooms.any { it.entityId == id } } ?: defaultRoom(rooms, self, area, lastRoom)
}
