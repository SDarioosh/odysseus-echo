package com.msp1974.vacompanion.odysseus.followup

import com.msp1974.vacompanion.odysseus.followup.FollowUpRules.Conditions
import com.msp1974.vacompanion.odysseus.followup.FollowUpRules.Skip
import com.msp1974.vacompanion.satellite.PipelineEndReason
import com.msp1974.vacompanion.satellite.PipelineStartMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpRulesTest {
    private val ok = Conditions(
        enabled = true, sameTurn = true, muted = false, micMuted = false, doNotDisturb = false,
        recording = false, screenBlanked = false, busy = false, awaitingConfirmation = true,
    )

    @Test
    fun `offered after a wake word turn that ended normally`() {
        assertTrue(FollowUpRules.offersAfter(true, PipelineStartMode.WAKE_WORD_DETECTED, PipelineEndReason.END_OF_PIPELINE, haContinues = false))
    }

    @Test
    fun `not offered when turned off`() {
        assertFalse(FollowUpRules.offersAfter(false, PipelineStartMode.WAKE_WORD_DETECTED, PipelineEndReason.END_OF_PIPELINE, false))
    }

    @Test
    fun `not offered when Home Assistant continues the conversation itself`() {
        assertFalse(FollowUpRules.offersAfter(true, PipelineStartMode.WAKE_WORD_DETECTED, PipelineEndReason.END_OF_PIPELINE, haContinues = true))
    }

    @Test
    fun `one try only - never after a follow-up or a continued conversation`() {
        assertFalse(FollowUpRules.offersAfter(true, PipelineStartMode.CONTINUE_CONVERSATION, PipelineEndReason.END_OF_PIPELINE, false))
    }

    @Test
    fun `not after a server question or an announcement`() {
        assertFalse(FollowUpRules.offersAfter(true, PipelineStartMode.REQUESTED_BY_SERVER, PipelineEndReason.END_OF_PIPELINE, false))
        assertFalse(FollowUpRules.offersAfter(true, PipelineStartMode.START_STREAM_TTS, PipelineEndReason.END_OF_PIPELINE, false))
    }

    @Test
    fun `not after a barge-in, time-out or error`() {
        listOf(
            PipelineEndReason.FORCE_STOPPED, PipelineEndReason.TIMED_OUT, PipelineEndReason.ERRORED,
            PipelineEndReason.DUPLICATE_WAKEUP, PipelineEndReason.NONE,
        ).forEach {
            assertFalse(it.name, FollowUpRules.offersAfter(true, PipelineStartMode.WAKE_WORD_DETECTED, it, false))
        }
    }

    @Test
    fun `listens when everything allows it`() {
        assertNull(FollowUpRules.skipReason(ok))
    }

    @Test
    fun `each guard stops it`() {
        assertEquals(Skip.DISABLED, FollowUpRules.skipReason(ok.copy(enabled = false)))
        assertEquals(Skip.NEW_TURN, FollowUpRules.skipReason(ok.copy(sameTurn = false)))
        assertEquals(Skip.MUTED, FollowUpRules.skipReason(ok.copy(muted = true)))
        assertEquals(Skip.MIC_MUTED, FollowUpRules.skipReason(ok.copy(micMuted = true)))
        assertEquals(Skip.DO_NOT_DISTURB, FollowUpRules.skipReason(ok.copy(doNotDisturb = true)))
        assertEquals(Skip.RECORDING, FollowUpRules.skipReason(ok.copy(recording = true)))
        assertEquals(Skip.SCREEN_BLANKED, FollowUpRules.skipReason(ok.copy(screenBlanked = true)))
        assertEquals(Skip.BUSY, FollowUpRules.skipReason(ok.copy(busy = true)))
        assertEquals(Skip.NOT_AWAITING, FollowUpRules.skipReason(ok.copy(awaitingConfirmation = false)))
    }

    @Test
    fun `cheap checks pass before the hub is asked`() {
        assertNull(FollowUpRules.skipReason(ok.copy(awaitingConfirmation = null)))
        assertEquals(Skip.MUTED, FollowUpRules.skipReason(ok.copy(awaitingConfirmation = null, muted = true)))
    }

    @Test
    fun `the first reason wins`() {
        assertEquals(Skip.MUTED, FollowUpRules.skipReason(ok.copy(muted = true, recording = true, awaitingConfirmation = false)))
    }

    @Test
    fun `seconds are clamped to 3 to 15`() {
        assertEquals(3, FollowUpRules.clampSeconds(0))
        assertEquals(3, FollowUpRules.clampSeconds(-5))
        assertEquals(8, FollowUpRules.clampSeconds(8))
        assertEquals(15, FollowUpRules.clampSeconds(60))
        assertEquals(8_000L, FollowUpRules.listenLimitMs(FollowUpRules.DEFAULT_SECONDS))
        assertEquals(15_000L, FollowUpRules.listenLimitMs(99))
        assertTrue(FollowUpRules.SECONDS_CHOICES.all { it in FollowUpRules.SECONDS_RANGE })
        assertTrue(FollowUpRules.DEFAULT_SECONDS in FollowUpRules.SECONDS_CHOICES)
    }

    @Test
    fun `listening ends after the limit, not before`() {
        assertFalse(FollowUpRules.listeningExpired(7_999, 8_000))
        assertFalse(FollowUpRules.listeningExpired(8_000, 8_000))
        assertTrue(FollowUpRules.listeningExpired(8_001, 8_000))
    }

    @Test
    fun `only nothing heard ends quietly`() {
        assertTrue(FollowUpRules.isNothingHeard("stt-no-text-recognized"))
        assertFalse(FollowUpRules.isNothingHeard("stt-stream-failed"))
        assertFalse(FollowUpRules.isNothingHeard("intent-failed"))
    }

    @Test
    fun `a satellite that stopped meanwhile never starts the follow-up`() {
        // HA reconnected while the answer played: the old satellite must not open a pipeline.
        assertTrue(FollowUpRules.canStartListening(running = true, connected = true, responding = false, arbitrating = false))
        assertFalse(FollowUpRules.canStartListening(running = false, connected = true, responding = false, arbitrating = false))
        assertFalse(FollowUpRules.canStartListening(running = true, connected = false, responding = false, arbitrating = false))
        assertFalse(FollowUpRules.canStartListening(running = true, connected = true, responding = true, arbitrating = false))
        assertFalse(FollowUpRules.canStartListening(running = true, connected = true, responding = false, arbitrating = true))
    }
}
