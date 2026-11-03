package com.msp1974.vacompanion.odysseus

import com.msp1974.vacompanion.satellite.PipelineStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingChimeTest {

    private fun play(
        enabled: Boolean = true,
        stage: PipelineStage = PipelineStage.AWAITING_RESPONSE,
        alreadyPlayed: Boolean = false,
        muted: Boolean = false,
        doNotDisturb: Boolean = false,
        micMuted: Boolean = false,
    ) = ThinkingChime.shouldPlay(enabled, stage, alreadyPlayed, muted, doNotDisturb, micMuted)

    @Test
    fun playsWhileStillWaitingForTheAnswer() = assertTrue(play())

    @Test
    fun onlyOncePerTurn() = assertFalse(play(alreadyPlayed = true))

    @Test
    fun notWhenOff() = assertFalse(play(enabled = false))

    @Test
    fun notOnceTtsIsComingOrTheTurnEnded() {
        for (s in listOf(PipelineStage.AWAITING_TTS, PipelineStage.STARTING_TTS, PipelineStage.STREAMING_TTS,
            PipelineStage.COMPLETED_TTS, PipelineStage.ENDED)) {
            assertFalse(s.name, play(stage = s))
        }
    }

    @Test
    fun notWhileMutedOrInDoNotDisturb() {
        assertFalse(play(muted = true))
        assertFalse(play(doNotDisturb = true))
        assertFalse(play(micMuted = true))
    }
}
