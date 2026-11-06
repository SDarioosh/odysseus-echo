package com.msp1974.vacompanion.odysseus.record

import com.msp1974.vacompanion.odysseus.record.RecorderEffect.EndSession
import com.msp1974.vacompanion.odysseus.record.RecorderEffect.PlayStartSound
import com.msp1974.vacompanion.odysseus.record.RecorderEffect.PlayStopSound
import com.msp1974.vacompanion.odysseus.record.RecorderEffect.Refused
import com.msp1974.vacompanion.odysseus.record.RecorderEffect.StartSession
import com.msp1974.vacompanion.odysseus.record.RecordingKind.NOTE
import com.msp1974.vacompanion.odysseus.record.RecordingKind.RECORDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderStateMachineTest {

    private val t0 = 1_000_000L
    private fun m() = RecorderStateMachine()

    private fun recording(): RecorderStateMachine = m().also { it.press(t0) }
    private fun note(): RecorderStateMachine = m().also { it.hold(t0) }
    private fun noteDuringRecording(): RecorderStateMachine = m().also { it.press(t0); it.hold(t0 + 5_000) }

    // --- press ---

    @Test
    fun pressWhenIdleStartsARecording() {
        val sm = m()
        assertEquals(listOf(StartSession(RECORDING), PlayStartSound), sm.press(t0))
        assertEquals(RecorderState.Recording(t0), sm.state)
    }

    @Test
    fun pressWhileRecordingStopsIt() {
        val sm = recording()
        assertEquals(listOf(EndSession(RECORDING), PlayStopSound), sm.press(t0 + 10_000))
        assertEquals(RecorderState.Idle, sm.state)
    }

    @Test
    fun pressDuringANoteStopsTheNote() {
        val sm = note()
        assertEquals(listOf(EndSession(NOTE), PlayStopSound), sm.press(t0 + 10_000))
        assertEquals(RecorderState.Idle, sm.state)
    }

    @Test
    fun pressDuringANoteThatInterruptedARecordingStartsANewRecordingQuietly() {
        val sm = noteDuringRecording()
        val now = t0 + 20_000
        assertEquals(listOf(EndSession(NOTE), PlayStopSound, StartSession(RECORDING, quiet = true)), sm.press(now))
        assertEquals(RecorderState.Recording(now), sm.state)
    }

    // --- hold ---

    @Test
    fun holdWhenIdleStartsANote() {
        val sm = m()
        assertEquals(listOf(StartSession(NOTE), PlayStartSound), sm.hold(t0))
        assertEquals(RecorderState.Note(t0, resumeRecording = false), sm.state)
    }

    @Test
    fun holdWhileRecordingEndsTheRecordingAndStartsANote() {
        val sm = recording()
        val now = t0 + 5_000
        assertEquals(listOf(EndSession(RECORDING), StartSession(NOTE), PlayStartSound), sm.hold(now))
        assertEquals(RecorderState.Note(now, resumeRecording = true), sm.state)
    }

    @Test
    fun holdDuringANoteStopsIt() {
        val sm = note()
        assertEquals(listOf(EndSession(NOTE), PlayStopSound), sm.hold(t0 + 10_000))
        assertEquals(RecorderState.Idle, sm.state)
    }

    @Test
    fun holdDuringANoteThatInterruptedARecordingResumesRecording() {
        val sm = noteDuringRecording()
        val effects = sm.hold(t0 + 20_000)
        assertEquals(StartSession(RECORDING, quiet = true), effects.last())
        assertTrue(sm.state is RecorderState.Recording)
    }

    // --- debounce ---

    @Test
    fun aSecondPressWithinOneSecondIsIgnored() {
        val sm = m()
        sm.press(t0)
        assertEquals(emptyList<RecorderEffect>(), sm.press(t0 + 999))
        assertTrue(sm.state is RecorderState.Recording)
        assertEquals(listOf(EndSession(RECORDING), PlayStopSound), sm.press(t0 + 1_000))
    }

    @Test
    fun aHoldWithinOneSecondOfAPressIsIgnored() {
        val sm = m()
        sm.press(t0)
        assertEquals(emptyList<RecorderEffect>(), sm.hold(t0 + 500))
        assertTrue(sm.state is RecorderState.Recording)
    }

    @Test
    fun aRefusedPressDoesNotStartTheDebounce() {
        val sm = m()
        assertEquals(listOf(Refused("muted")), sm.press(t0, refusal = "muted"))
        assertEquals(listOf(StartSession(RECORDING), PlayStartSound), sm.press(t0 + 100))
    }

    // --- refusals ---

    @Test
    fun nothingStartsWhileMuted() {
        val sm = m()
        assertEquals(listOf(Refused("muted")), sm.press(t0, "muted"))
        assertEquals(listOf(Refused("muted")), sm.hold(t0 + 2_000, "muted"))
        assertEquals(RecorderState.Idle, sm.state)
    }

    @Test
    fun aRefusalDoesNotStopAPress() {
        // Stopping is always allowed, muted or not.
        val sm = recording()
        assertEquals(listOf(EndSession(RECORDING), PlayStopSound), sm.press(t0 + 5_000, "muted"))
        val n = note()
        assertEquals(listOf(EndSession(NOTE), PlayStopSound), n.press(t0 + 5_000, "muted"))
    }

    @Test
    fun aHoldWhileRecordingAndMutedStopsTheRecordingAndStartsNoNote() {
        val sm = recording()
        assertEquals(listOf(EndSession(RECORDING), PlayStopSound, Refused("muted")), sm.hold(t0 + 5_000, "muted"))
        assertEquals(RecorderState.Idle, sm.state)
    }

    // --- maximum lengths ---

    @Test
    fun aNoteStopsByItselfAfterFiveMinutes() {
        val sm = note()
        assertEquals(emptyList<RecorderEffect>(), sm.tick(t0 + RecorderStateMachine.MAX_NOTE_MS - 1))
        assertEquals(listOf(EndSession(NOTE), PlayStopSound), sm.tick(t0 + RecorderStateMachine.MAX_NOTE_MS))
        assertEquals(RecorderState.Idle, sm.state)
    }

    @Test
    fun aNoteDuringARecordingThatTimesOutResumesTheRecording() {
        val sm = noteDuringRecording()
        val end = t0 + 5_000 + RecorderStateMachine.MAX_NOTE_MS
        assertEquals(listOf(EndSession(NOTE), PlayStopSound, StartSession(RECORDING, quiet = true)), sm.tick(end))
        assertEquals(RecorderState.Recording(end), sm.state)
    }

    @Test
    fun aRecordingStopsByItselfAfterFourHours() {
        val sm = recording()
        assertEquals(emptyList<RecorderEffect>(), sm.tick(t0 + RecorderStateMachine.MAX_RECORDING_MS - 1))
        assertEquals(listOf(EndSession(RECORDING), PlayStopSound), sm.tick(t0 + RecorderStateMachine.MAX_RECORDING_MS))
    }

    @Test
    fun tickWhenIdleDoesNothing() {
        assertEquals(emptyList<RecorderEffect>(), m().tick(t0))
    }

    // --- stopAll (muted, turned off) ---

    @Test
    fun stopAllEndsWhateverRunsAndResumesNothing() {
        assertEquals(emptyList<RecorderEffect>(), m().stopAll())
        val r = recording()
        assertEquals(listOf(EndSession(RECORDING), PlayStopSound), r.stopAll())
        assertEquals(RecorderState.Idle, r.state)
        val n = noteDuringRecording()
        assertEquals(listOf(EndSession(NOTE), PlayStopSound), n.stopAll())
        assertEquals(RecorderState.Idle, n.state)
    }
}
