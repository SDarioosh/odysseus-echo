package com.msp1974.vacompanion.odysseus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeIndicatorPolicyTest {

    private val shown = mutableListOf<String>()
    private val policy = WakeIndicatorPolicy(show = { shown += "listening" }, hide = { shown += "idle" })

    @Test
    fun aWakeWordShowsListeningAtOnce() {
        policy.wakeHeard()
        assertEquals(listOf("listening"), shown)
        assertTrue(policy.isPending)
    }

    // Every way a wake word ends without a turn reaches wakeEnded() through the handler's finally:
    // lost arbitration, a late report told it lost, Lock mode, HA's run never ending, dropped audio,
    // an exception or a cancelled coroutine.
    @Test
    fun aWakeWordThatEndsWithoutATurnGoesBackToIdleAtOnce() {
        policy.wakeHeard()
        policy.wakeEnded()
        assertEquals(listOf("listening", "idle"), shown)
        assertFalse(policy.isPending)
    }

    @Test
    fun aWakeWordThatStartedATurnLeavesTheIndicatorToThePipeline() {
        policy.wakeHeard()
        policy.turnStarted()
        policy.wakeEnded()
        assertEquals(listOf("listening"), shown)
    }

    @Test
    fun endingTwiceHidesOnce() {
        policy.wakeHeard()
        policy.wakeEnded()
        policy.wakeEnded()
        assertEquals(listOf("listening", "idle"), shown)
    }

    @Test
    fun aWakeWordIgnoredBeforeTheIndicatorShowedChangesNothing() {
        // Lock mode, no HA connection, already arbitrating, calibration, a training take: the handler
        // returns (or is never called) before showListening(); its finally may still run.
        policy.wakeEnded()
        assertEquals(emptyList<String>(), shown)
    }

    @Test
    fun aTurnStartedByHomeAssistantWhileArbitratingKeepsItsIndicator() {
        // An announcement's pipeline starts while the wake word is arbitrated, and the wake word loses:
        // the announcement owns the indicator, so losing must not hide it.
        policy.wakeHeard()
        policy.turnStarted()
        policy.wakeEnded()
        assertFalse(shown.contains("idle"))
    }

    @Test
    fun anOlderTurnsStagesDontDriveTheIndicatorWhileAWakeWordIsPending() {
        // A barge-in stops the old pipeline; its ENDED arrives after the new "Listening…".
        policy.wakeHeard()
        assertFalse(policy.stageShown())
        policy.turnStarted()
        assertTrue(policy.stageShown())
    }

    @Test
    fun withNoWakeWordPendingStagesDriveTheIndicator() {
        assertTrue(policy.stageShown())
        policy.wakeHeard()
        policy.wakeEnded()
        assertTrue(policy.stageShown())
    }

    @Test
    fun aSecondWakeWordAfterTheFirstEndedShowsAgain() {
        policy.wakeHeard()
        policy.wakeEnded()
        policy.wakeHeard()
        policy.wakeEnded()
        assertEquals(listOf("listening", "idle", "listening", "idle"), shown)
    }
}
