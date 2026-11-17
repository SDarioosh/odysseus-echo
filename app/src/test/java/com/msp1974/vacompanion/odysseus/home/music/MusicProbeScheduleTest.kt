package com.msp1974.vacompanion.odysseus.home.music

import com.msp1974.vacompanion.odysseus.music.MusicRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicProbeScheduleTest {
    @Test fun roomsLostOnlyAfterHavingRooms() {
        assertTrue(MusicProbeSchedule.roomsLost(hadRooms = true, hasRooms = false))
        assertFalse(MusicProbeSchedule.roomsLost(hadRooms = false, hasRooms = false)) // never had any
        assertFalse(MusicProbeSchedule.roomsLost(hadRooms = true, hasRooms = true))
    }

    @Test fun normallyEveryTenMinutes() {
        assertEquals(MusicRepository.PROBE_MS, MusicProbeSchedule.delayMs(0, roomsLost = false))
        assertEquals(MusicRepository.PROBE_MS, MusicProbeSchedule.delayMs(3, roomsLost = false))
    }

    @Test fun backsOffWhileRoomsAreGone() {
        val waits = (0..6).map { MusicProbeSchedule.delayMs(it, roomsLost = true) }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L), waits)
        assertEquals(MusicRepository.PROBE_MS, MusicProbeSchedule.delayMs(1_000, roomsLost = true))
    }
}
