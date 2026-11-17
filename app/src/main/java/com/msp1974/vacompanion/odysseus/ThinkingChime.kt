package com.msp1974.vacompanion.odysseus

// Odysseus fork: the "thinking" sound. When Home Assistant has the question (AWAITING_RESPONSE)
// but no answer has started after DELAY_MS, play one short soft tone, so a long Odysseus turn
// (tool calls on a local model) doesn't sound like the device stopped listening. At most once per
// turn, through the satellite's sound-effect player. The sound is bundled
// (assets/odysseus/thinking.wav, made by scripts/odysseus/make_sounds.py).

import com.msp1974.vacompanion.satellite.PipelineStage

object ThinkingChime {
    const val DELAY_MS = 5_000L
    const val SOUND_URI = "asset:///odysseus/thinking.wav"

    /**
     * Whether to play the sound now, DELAY_MS after the turn entered AWAITING_RESPONSE.
     * [stage] is the turn's stage now: once TTS is on its way (AWAITING_TTS or later), or the turn
     * ended (a barge-in stops it), it's too late. Never while muted or in do-not-disturb.
     */
    fun shouldPlay(
        enabled: Boolean,
        stage: PipelineStage,
        alreadyPlayed: Boolean,
        muted: Boolean,
        doNotDisturb: Boolean,
        micMuted: Boolean,
    ): Boolean = enabled && !alreadyPlayed && stage == PipelineStage.AWAITING_RESPONSE &&
        !muted && !doNotDisturb && !micMuted
}
