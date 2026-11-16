package com.msp1974.vacompanion.odysseus.arbitration

import com.msp1974.vacompanion.broadcasts.WakeWordArbiter.Contender
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeDecisionLogTest {

    private val a = Contender("1a2b3c4d9999-1", "1a2b3c4d9999", 0.99f, -29.6f, "hey athena", noiseFloorDb = -61f, receivedAtMs = 1000L)
    private val b = Contender("5e6f7a8b9999-4", "5e6f7a8b9999", 0.97f, -32.0f, "hey athena", noiseFloorDb = -58.3f, receivedAtMs = 1023L)

    @Test
    fun theDecisionLineHasTheRuleAndEveryReportersInputs() {
        val pick = WakeWordPickRules.pick(listOf(a, b), null)
        assertEquals(
            "winner 1a2b3c4d by loudest (+2.4 dB) from 2 report(s): " +
                "1a2b3c4d -29.6 dB (floor -61.0), score 0.99, +0 ms | 5e6f7a8b -32.0 dB (floor -58.3), score 0.97, +23 ms",
            WakeDecisionLog.decision(pick, listOf(a, b)),
        )
    }

    @Test
    fun missingInputsAreShownAsMissing() {
        val old = Contender("cafe0000-1", "cafe0000", 0.9f, null, responding = true)
        assertEquals("cafe0000 ? dB, score 0.90, responding", WakeDecisionLog.report(old))
    }

    @Test
    fun theStickyRuleShowsHowFarBehindTheLoudestItWas() {
        val near = b.copyLevel(-30.8f)
        val pick = WakeWordPickRules.pick(listOf(a, near), lastHandler = "5e6f7a8b9999")
        assertEquals(
            "winner 5e6f7a8b by answered last, within 1.5 dB of the loudest (-1.2 dB) from 2 report(s): " +
                "1a2b3c4d -29.6 dB (floor -61.0), score 0.99, +0 ms | 5e6f7a8b -30.8 dB (floor -58.3), score 0.97, +23 ms",
            WakeDecisionLog.decision(pick, listOf(a, near)),
        )
    }

    private fun Contender.copyLevel(level: Float) =
        Contender(id, sender, score, level, wakeWord, responding, noiseFloorDb, receivedAtMs)
}
