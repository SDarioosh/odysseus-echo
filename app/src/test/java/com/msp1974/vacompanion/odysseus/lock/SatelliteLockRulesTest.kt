package com.msp1974.vacompanion.odysseus.lock

import com.msp1974.vacompanion.odysseus.lock.SatelliteLockRules.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteLockRulesTest {
    private fun decide(type: String, locked: Boolean, dropping: Boolean = false, pipeline: Boolean = false) =
        SatelliteLockRules.serverPacket(type, locked, dropping) { pipeline }

    @Test
    fun unlockedNothingIsRefusedAndThePipelineIsNotAsked() {
        for (type in listOf("audio-start", "audio-chunk", "audio-stop", "transcribe", "synthesize")) {
            assertEquals(Packet.PASS, SatelliteLockRules.serverPacket(type, false, false) { error("not asked") })
        }
    }

    @Test
    fun lockedAnAnnouncementIsDroppedAndAnsweredPlayed() {
        assertEquals(Packet.START_DROPPING, decide("audio-start", locked = true))
        assertEquals(Packet.DROP, decide("audio-chunk", locked = true, dropping = true))
        assertEquals(Packet.STOP_DROPPING, decide("audio-stop", locked = true, dropping = true))
    }

    @Test
    fun anAnnouncementDroppedWhenUnlockingMidwayIsDroppedToItsEnd() {
        assertEquals(Packet.DROP, decide("audio-chunk", locked = false, dropping = true))
        assertEquals(Packet.STOP_DROPPING, decide("audio-stop", locked = false, dropping = true))
        // but a new one is not dropped
        assertEquals(Packet.PASS, decide("audio-start", locked = false, dropping = true))
        assertEquals(Packet.PASS, decide("transcribe", locked = false, dropping = true))
    }

    @Test
    fun lockedAConversationHaStartsIsRefused() {
        assertEquals(Packet.REFUSE, decide("transcribe", locked = true))
    }

    @Test
    fun lockedChunksAndStopsOfNoDroppedAnnouncementPass() {
        assertEquals(Packet.PASS, decide("audio-chunk", locked = true))
        assertEquals(Packet.PASS, decide("audio-stop", locked = true))
        assertEquals(Packet.PASS, decide("synthesize", locked = true))
    }

    @Test
    fun aRunningPipelineGetsItsOwnPackets() {
        for (type in listOf("audio-start", "audio-chunk", "audio-stop", "transcribe")) {
            assertEquals(Packet.PASS, decide(type, locked = true, dropping = true, pipeline = true))
        }
    }

    @Test
    fun actionsThatWakePlayOrShowAreIgnoredWhileLocked() {
        for (a in listOf("play-media", "play", "set-volume", "toast-message", "wake", "alarm", "navigate")) {
            assertTrue(a, SatelliteLockRules.ignoresAction(true, a))
            assertFalse(a, SatelliteLockRules.ignoresAction(false, a))
        }
        for (a in listOf("pause", "stop", "interrupt", "refresh", "get-logs")) {
            assertFalse(a, SatelliteLockRules.ignoresAction(true, a))
        }
    }
}
