package com.msp1974.vacompanion.odysseus

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HaRunGateTest {

    @Before
    fun fresh() = HaRunGate.reset()

    @Test
    fun noRunNothingToInterrupt() {
        assertFalse(HaRunGate.interrupted())
        assertFalse(HaRunGate.onServerPacket("audio-start"))
    }

    @Test
    fun aRunIsInterruptedOnceSoOdysseusIsAskedOnce() {
        HaRunGate.runStarted()
        assertTrue(HaRunGate.interrupted())
        // the second wake word path (handleWakeWordDetection) sends nothing more
        assertFalse(HaRunGate.interrupted())
    }

    @Test
    fun theInterruptedRunsEventsAreDroppedAndItsEndOpensTheGate() = runBlocking {
        HaRunGate.runStarted()
        HaRunGate.interrupted()
        val waiting = async { HaRunGate.awaitIdle() }
        delay(50)
        assertTrue(HaRunGate.onServerPacket("synthesize"))
        assertTrue(HaRunGate.onServerPacket("pipeline-ended"))
        assertTrue(waiting.await())
        // its answer audio, sent after its end, is dropped too
        assertTrue(HaRunGate.onServerPacket("audio-start"))
        assertTrue(HaRunGate.onServerPacket("audio-stop"))
        // the new run's events go through
        assertFalse(HaRunGate.onServerPacket("transcribe"))
        assertFalse(HaRunGate.onServerPacket("transcript"))
        assertFalse(HaRunGate.onServerPacket("audio-start"))
    }

    @Test
    fun aRunEndedNormallyIsNotDropped() {
        HaRunGate.runStarted()
        assertFalse(HaRunGate.onServerPacket("pipeline-ended"))
        assertFalse(HaRunGate.interrupted())
    }

    @Test
    fun theInterruptAnswerIsRead() {
        assertEquals(
            OdysseusLink.InterruptResult.Ok(1, 0),
            VoiceTurnApi.parseInterruptResponse("""{"ok": true, "cut": 1, "marked": 0}"""),
        )
        assertEquals(
            OdysseusLink.InterruptResult.Ok(0, 0),
            VoiceTurnApi.parseInterruptResponse("""{"ok": true}"""),
        )
    }
}
