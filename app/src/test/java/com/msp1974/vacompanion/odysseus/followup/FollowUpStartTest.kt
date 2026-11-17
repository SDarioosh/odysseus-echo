package com.msp1974.vacompanion.odysseus.followup

import com.msp1974.vacompanion.odysseus.HaRunGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FollowUpStartTest {
    private class FakeHost(
        override var running: Boolean = true,
        override val connected: Boolean = true,
        var respondingNow: Boolean = false,
        val respondAfterSound: Boolean = false,
    ) : FollowUp.Host<String> {
        val calls = mutableListOf<String>()
        override var arbitrating = false
        var arbitratingDuringSound: Boolean? = null
        override fun responding() = respondingNow
        override suspend fun playSound() {
            calls += "sound"; arbitratingDuringSound = arbitrating
            if (respondAfterSound) respondingNow = true
        }
        override suspend fun duckOthers() { calls += "duck" }
        override fun keepAudio(): String { calls += "keep"; return "buffer" }
        override fun dropAudio() { calls += "drop" }
        override fun startPipeline(buffer: String, listenLimitMs: Long) { calls += "start:$buffer:$listenLimitMs" }
    }

    @Before
    fun fresh() = HaRunGate.reset()

    @Test
    fun startsInOrderAndHoldsTheWakeWordMeanwhile() = runBlocking {
        val host = FakeHost()
        assertTrue(FollowUp.startListening(host, 8000))
        assertEquals(listOf("sound", "duck", "keep", "start:buffer:8000"), host.calls)
        assertEquals(true, host.arbitratingDuringSound)
        assertFalse(host.arbitrating)
    }

    @Test
    fun notWhenBusyOrGone() = runBlocking {
        assertFalse(FollowUp.startListening(FakeHost(running = false), 8000))
        assertFalse(FollowUp.startListening(FakeHost(connected = false), 8000))
        assertFalse(FollowUp.startListening(FakeHost(respondingNow = true), 8000))
        val arbitrating = FakeHost().also { it.arbitrating = true }
        assertFalse(FollowUp.startListening(arbitrating, 8000))
        assertTrue(arbitrating.calls.isEmpty())
        assertTrue(arbitrating.arbitrating) // someone else's, left alone
    }

    @Test
    fun aTurnStartedDuringTheSoundWins() = runBlocking {
        val host = FakeHost(respondAfterSound = true)
        assertFalse(FollowUp.startListening(host, 8000))
        assertEquals(listOf("sound"), host.calls)
        assertFalse(host.arbitrating)
    }
}
