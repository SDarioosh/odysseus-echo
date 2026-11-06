package com.msp1974.vacompanion.odysseus.record

import com.msp1974.vacompanion.odysseus.record.ButtonAction.Hold
import com.msp1974.vacompanion.odysseus.record.ButtonAction.Press
import com.msp1974.vacompanion.odysseus.record.ButtonAction.StartHoldTimer
import com.msp1974.vacompanion.odysseus.record.ButtonAction.StartSwitchTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordButtonGesturesTest {

    private val t0 = 1_000_000L
    private val hold = RecorderStateMachine.HOLD_MS
    private val wait = RecordButtonGestures.SWITCH_WAIT_MS
    private val covers = RecordButtonGestures.KEY_COVERS_SWITCH_MS
    private fun g() = RecordButtonGestures()

    // --- keys ---

    @Test
    fun downStartsTheHoldTimer() {
        assertEquals(listOf(StartHoldTimer(hold)), g().keyDown(t0))
    }

    @Test
    fun upBeforeOneSecondIsAPress() {
        val g = g()
        g.keyDown(t0)
        assertEquals(listOf(Press), g.keyUp(t0 + 200))
        // The timer of that down fires later and does nothing.
        assertEquals(emptyList<ButtonAction>(), g.holdTimerFired(t0 + hold))
    }

    @Test
    fun stillDownAtOneSecondIsAHoldAndTheUpAfterItDoesNothing() {
        val g = g()
        g.keyDown(t0)
        assertEquals(listOf(Hold), g.holdTimerFired(t0 + hold))
        assertEquals(emptyList<ButtonAction>(), g.holdTimerFired(t0 + hold + 50))
        assertEquals(emptyList<ButtonAction>(), g.keyUp(t0 + 3_000))
    }

    @Test
    fun aTimerFiringEarlyDoesNothing() {
        val g = g()
        g.keyDown(t0)
        assertEquals(emptyList<ButtonAction>(), g.holdTimerFired(t0 + hold - 1))
    }

    @Test
    fun keyRepeatsAreIgnored() {
        val g = g()
        g.keyDown(t0)
        assertEquals(emptyList<ButtonAction>(), g.keyDown(t0 + 500, repeatCount = 1))
        assertEquals(emptyList<ButtonAction>(), g.keyDown(t0 + 550, repeatCount = 2))
        // Repeats don't restart the hold.
        assertEquals(listOf(Hold), g.holdTimerFired(t0 + hold))
    }

    @Test
    fun aStaleTimerFromAnEarlierDownDoesNotHoldTheNextPress() {
        val g = g()
        g.keyDown(t0)
        g.keyUp(t0 + 100)
        g.keyDown(t0 + 800)
        assertEquals(emptyList<ButtonAction>(), g.holdTimerFired(t0 + hold)) // first down's timer
        assertEquals(listOf(Press), g.keyUp(t0 + 900))
    }

    @Test
    fun anUpWithoutADownDoesNothing() {
        assertEquals(emptyList<ButtonAction>(), g().keyUp(t0))
    }

    @Test
    fun aSecondDownAfterALostUpStartsAgain() {
        val g = g()
        g.keyDown(t0)
        assertEquals(listOf(StartHoldTimer(hold)), g.keyDown(t0 + 5_000))
        assertEquals(emptyList<ButtonAction>(), g.holdTimerFired(t0 + hold)) // first timer: too early for the new down
        assertEquals(listOf(Hold), g.holdTimerFired(t0 + 5_000 + hold))
    }

    @Test
    fun downAndUpAtOnceIsAPress() {
        // If the button reports down and up together, every press is a press (no hold).
        val g = g()
        g.keyDown(t0)
        assertEquals(listOf(Press), g.keyUp(t0))
    }

    // --- the mute switch alone (no key events: fallback) ---

    @Test
    fun aSwitchChangeWithoutKeysIsAPressAfterTheWait() {
        val g = g()
        assertEquals(listOf(StartSwitchTimer(wait)), g.switchChanged(t0))
        assertEquals(emptyList<ButtonAction>(), g.switchTimerFired(t0 + wait - 1))
        assertEquals(listOf(Press), g.switchTimerFired(t0 + wait))
        assertEquals(emptyList<ButtonAction>(), g.switchTimerFired(t0 + wait + 10))
    }

    @Test
    fun everySwitchChangeIsAPress() {
        val g = g()
        g.switchChanged(t0)
        assertEquals(listOf(Press), g.switchTimerFired(t0 + wait))
        g.switchChanged(t0 + 5_000)
        assertEquals(listOf(Press), g.switchTimerFired(t0 + 5_000 + wait))
    }

    // --- keys and switch from the same press ---

    @Test
    fun aSwitchChangeDuringAKeyDownIsThatPress() {
        val g = g()
        g.keyDown(t0)
        assertEquals(emptyList<ButtonAction>(), g.switchChanged(t0 + 20))
        assertEquals(listOf(Press), g.keyUp(t0 + 150))
    }

    @Test
    fun aSwitchChangeRightAfterTheKeyIsThatPress() {
        val g = g()
        g.keyDown(t0)
        g.keyUp(t0 + 100)
        assertEquals(emptyList<ButtonAction>(), g.switchChanged(t0 + 100 + covers))
        assertEquals(emptyList<ButtonAction>(), g.switchTimerFired(t0 + 100 + covers + wait))
    }

    @Test
    fun aSwitchChangeAtTheReleaseOfAHoldIsNotAPress() {
        val g = g()
        g.keyDown(t0)
        assertEquals(listOf(Hold), g.holdTimerFired(t0 + hold))
        g.keyUp(t0 + 2_500)
        assertEquals(emptyList<ButtonAction>(), g.switchChanged(t0 + 2_520))
    }

    @Test
    fun aKeyDownArrivingJustAfterTheSwitchTakesThePress() {
        val g = g()
        assertEquals(listOf(StartSwitchTimer(wait)), g.switchChanged(t0))
        g.keyDown(t0 + 40)
        assertEquals(emptyList<ButtonAction>(), g.switchTimerFired(t0 + wait))
        assertEquals(listOf(Press), g.keyUp(t0 + 200))
    }

    @Test
    fun aHoldWhoseSwitchCameFirstIsStillOneHold() {
        val g = g()
        g.switchChanged(t0)
        g.keyDown(t0 + 30)
        assertEquals(emptyList<ButtonAction>(), g.switchTimerFired(t0 + wait))
        assertEquals(listOf(Hold), g.holdTimerFired(t0 + 30 + hold))
        assertEquals(emptyList<ButtonAction>(), g.keyUp(t0 + 2_000))
    }

    @Test
    fun aSwitchChangeLongAfterTheLastKeyIsAPress() {
        // Display off: keys don't reach the app, the switch still does.
        val g = g()
        g.keyDown(t0)
        g.keyUp(t0 + 100)
        val later = t0 + 60_000
        assertEquals(listOf(StartSwitchTimer(wait)), g.switchChanged(later))
        assertEquals(listOf(Press), g.switchTimerFired(later + wait))
    }

    @Test
    fun resetForgetsAGestureInProgress() {
        val g = g()
        g.keyDown(t0)
        g.reset()
        assertEquals(emptyList<ButtonAction>(), g.holdTimerFired(t0 + hold))
        assertEquals(emptyList<ButtonAction>(), g.keyUp(t0 + hold + 100))
        g.switchChanged(t0 + 10_000)
        g.reset()
        assertEquals(emptyList<ButtonAction>(), g.switchTimerFired(t0 + 10_000 + wait))
    }

    // --- rules ---

    @Test
    fun theButtonIsFoundByItsInputDeviceNames() {
        assertTrue(PrivacyButtonRules.hasPrivacyButton(listOf("mtk-kpd", "gpio-privacy-button")))
        assertTrue(PrivacyButtonRules.hasPrivacyButton(listOf("gpio-privacy-state")))
        assertFalse(PrivacyButtonRules.hasPrivacyButton(listOf("gpio-keys", "Virtual")))
        assertFalse(PrivacyButtonRules.hasPrivacyButton(emptyList()))
    }

    @Test
    fun theButtonRecordsOnlyWithTheButtonInRecordModeWithRecordingOn() {
        assertTrue(PrivacyButtonRules.isRecordButton(true, PrivacyButtonMode.RECORD, true))
        assertFalse(PrivacyButtonRules.isRecordButton(false, PrivacyButtonMode.RECORD, true))
        assertFalse(PrivacyButtonRules.isRecordButton(true, PrivacyButtonMode.MUTE, true))
        assertFalse(PrivacyButtonRules.isRecordButton(true, PrivacyButtonMode.RECORD, false))
    }

    @Test
    fun systemMuteIsIgnoredOnlyWhileTheButtonRecords() {
        assertFalse(PrivacyButtonRules.systemMuteCounts(rawMuted = true, recordButton = true))
        assertTrue(PrivacyButtonRules.systemMuteCounts(rawMuted = true, recordButton = false))
        assertFalse(PrivacyButtonRules.systemMuteCounts(rawMuted = false, recordButton = false))
    }

    @Test
    fun modeKeysRoundTripAndDefaultToRecord() {
        for (m in PrivacyButtonMode.entries) assertEquals(m, PrivacyButtonMode.fromKey(m.key))
        assertEquals(PrivacyButtonMode.RECORD, PrivacyButtonMode.fromKey(null))
        assertEquals(PrivacyButtonMode.RECORD, PrivacyButtonMode.fromKey("bogus"))
    }
}
