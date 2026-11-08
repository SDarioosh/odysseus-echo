package com.msp1974.vacompanion.odysseus

// Odysseus fork: "Hey Athena, never mind" ends the turn on the device, unanswered.
//
// A wake word while the device is answering stops the answer and starts a new turn (barge-in,
// BargeIn.kt). Upstream checked for "never mind" only in a continued conversation, so on that new
// turn "never mind" went to Odysseus as a question and was answered out loud. Now, when a wake
// word turn's transcript is only a cancel phrase, the pipeline stops (SatelliteAudioPipeline
// .handleTranscript) and the run HA still has is interrupted like a barge-in: what HA sends for
// it is dropped (HaRunGate) and Odysseus is asked to end the turn (BargeIn).
//
// In a continued conversation (a follow-up, or HA asked for more) only "never mind" counts, as
// upstream: there "cancel", "no" and the like answer a question ("Add it to your calendar?"),
// which Odysseus must hear.

object Nevermind {
    /** Phrases that end a fresh wake word turn, after [normalize]. */
    val PHRASES = setOf(
        "never mind", "nevermind", "forget it", "forget about it", "forget that",
        "cancel", "cancel that", "stop talking", "be quiet", "quiet", "shut up", "shush",
        "nothing", "that's all", "thats all", "no thanks", "no thank you",
    )

    /** In a continued conversation only these end it (upstream's CONTINUATION_STOP_WORDS). */
    val CONTINUATION_PHRASES = setOf("never mind", "nevermind")

    // Words around the phrase that don't change it: "Athena, never mind", "oh, never mind then".
    private val FILLER = setOf("hey", "hi", "ok", "okay", "oh", "um", "uh", "athena", "actually", "please", "then", "just")

    /** Lower case, no punctuation, no filler words at either end. */
    fun normalize(text: String): String {
        val words = text.lowercase()
            .replace('’', '\'')
            .replace(Regex("[^a-z' ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .toMutableList()
        while (words.isNotEmpty() && words.first() in FILLER) words.removeAt(0)
        while (words.isNotEmpty() && words.last() in FILLER) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    /** True when [transcript] is only a cancel phrase, so the turn ends without an answer. */
    fun cancels(transcript: String, continuation: Boolean): Boolean {
        val said = normalize(transcript)
        if (said.isEmpty()) return false
        return said in (if (continuation) CONTINUATION_PHRASES else PHRASES)
    }
}
