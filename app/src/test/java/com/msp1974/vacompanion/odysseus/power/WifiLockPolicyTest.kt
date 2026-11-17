package com.msp1974.vacompanion.odysseus.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLockPolicyTest {

    private val policy = WifiLockPolicy()

    // ---- when the lock is held

    @Test
    fun nothingNeededMeansPowerSave() {
        assertFalse(policy.shouldHold(0))
        assertNull(policy.nextChangeAt(0))
    }

    @Test
    fun anyNeedHoldsTheLock() {
        policy.set(WifiNeed.CAMERA, true, 0)
        assertTrue(policy.shouldHold(0))
        assertEquals(setOf(WifiNeed.CAMERA), policy.reasons(0))
    }

    @Test
    fun theLockIsHeldWhileAnyNeedRemains() {
        policy.set(WifiNeed.ARBITRATION_MASTER, true, 0)
        policy.set(WifiNeed.CAMERA, true, 0)
        policy.set(WifiNeed.CAMERA, false, 10)
        assertEquals(setOf(WifiNeed.ARBITRATION_MASTER), policy.reasons(10))
        policy.set(WifiNeed.ARBITRATION_MASTER, false, 20)
        assertFalse(policy.shouldHold(20))
    }

    @Test
    fun turningANeedOffThatWasNeverOnChangesNothing() {
        policy.set(WifiNeed.VOICE, false, 0)
        assertFalse(policy.shouldHold(0))
        assertNull(policy.nextChangeAt(0))
    }

    // ---- grace

    @Test
    fun aVoiceInteractionKeepsTheLockForItsGraceAfterItEnds() {
        policy.set(WifiNeed.VOICE, true, 0)
        policy.set(WifiNeed.VOICE, false, 1_000)
        val grace = WifiNeed.VOICE.graceMs
        assertTrue(policy.shouldHold(1_000 + grace - 1))
        assertEquals(1_000 + grace, policy.nextChangeAt(1_000))
        assertFalse(policy.shouldHold(1_000 + grace))
    }

    @Test
    fun aNeedComingBackWithinItsGraceIsOnAgainWithoutAGap() {
        // A continued conversation: the next pipeline starts while the last one's grace runs.
        policy.set(WifiNeed.VOICE, true, 0)
        policy.set(WifiNeed.VOICE, false, 1_000)
        policy.set(WifiNeed.VOICE, true, 3_000)
        assertTrue(policy.shouldHold(1_000 + WifiNeed.VOICE.graceMs + 1))
    }

    @Test
    fun theMasterAndTheCameraHaveNoGrace() {
        policy.set(WifiNeed.ARBITRATION_MASTER, true, 0)
        policy.set(WifiNeed.ARBITRATION_MASTER, false, 5)
        assertFalse(policy.shouldHold(5))
        policy.set(WifiNeed.CAMERA, true, 10)
        policy.set(WifiNeed.CAMERA, false, 15)
        assertFalse(policy.shouldHold(15))
    }

    // ---- failsafe

    @Test
    fun aVoiceNeedNeverTurnedOffIsDroppedAfterItsLimit() {
        policy.set(WifiNeed.VOICE, true, 0)
        assertEquals(WifiNeed.VOICE.maxMs, policy.nextChangeAt(0))
        assertTrue(policy.shouldHold(WifiNeed.VOICE.maxMs - 1))
        assertFalse(policy.shouldHold(WifiNeed.VOICE.maxMs))
    }

    @Test
    fun settingANeedAgainRefreshesItsLimit() {
        policy.set(WifiNeed.VOICE, true, 0)
        policy.set(WifiNeed.VOICE, true, 60_000)
        assertTrue(policy.shouldHold(WifiNeed.VOICE.maxMs + 1))
    }

    @Test
    fun theMasterHasNoLimit() {
        policy.set(WifiNeed.ARBITRATION_MASTER, true, 0)
        assertNull(policy.nextChangeAt(0))
        assertTrue(policy.shouldHold(Long.MAX_VALUE / 2))
    }

    @Test
    fun theNextChangeIsTheEarliestGraceOrLimit() {
        policy.set(WifiNeed.VOICE, true, 0) // limit at maxMs
        policy.set(WifiNeed.SENDSPIN, true, 0)
        policy.set(WifiNeed.SENDSPIN, false, 100) // grace ends at 100 + graceMs
        assertEquals(100 + WifiNeed.SENDSPIN.graceMs, policy.nextChangeAt(100))
    }

    // ---- lock modes per Android version

    @Test
    fun beforeAndroid10OnlyHighPerformanceExists() {
        assertEquals(listOf(WifiLockMode.HIGH_PERF), WifiLockPolicy.lockModes(26))
        assertEquals(listOf(WifiLockMode.HIGH_PERF), WifiLockPolicy.lockModes(28))
    }

    @Test
    fun fromAndroid10BothAreHeldSoScreenOffStillWorks() {
        // Low latency only applies with the screen on and the app visible; high performance covers
        // the screen-off case (both Echo Show 5 and Fire HD 10 run Android 11).
        val both = listOf(WifiLockMode.HIGH_PERF, WifiLockMode.LOW_LATENCY)
        assertEquals(both, WifiLockPolicy.lockModes(29))
        assertEquals(both, WifiLockPolicy.lockModes(30))
        assertEquals(both, WifiLockPolicy.lockModes(34))
    }
}
