package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork: the rules for catching wake word clips from what the wake word model hears
// (docs/odysseus/wake-word-training.md). Plain Kotlin, unit-tested (WakeClipCatcherTest).
//
// Fed one microphone frame at a time with the model's score for it ([onFrame]), the same frames
// the model reads. It keeps the last [RING_MS] in memory only, and hands a clip out only when:
//   * a guided take is running (the training screen): the take's whole window, if anyone spoke;
//   * catching misses is on, the model scored a sound at [nearMissFloor] or more without the
//     device waking, and the device then woke within [PAIR_WINDOW_MS]: the owner most likely
//     said the wake word, wasn't heard, and said it again. Both the miss and the wake that
//     followed are handed out. A near miss that isn't followed by a wake is forgotten.

/** One clip: 16 kHz mono PCM16. [score] is the model's highest score in it. */
class WakeClip(
    val kind: Kind,
    val pcm: ShortArray,
    val score: Float,
    val threshold: Float,
    val recordedAt: Long,
    val prompt: String = "",
) {
    enum class Kind(val wire: String) { GUIDED("guided"), MISSED("missed"), HEARD("heard") }
}

class WakeClipCatcher(private val nearMissFloor: Float = NEAR_MISS_FLOOR) {
    private class Frame(val pcm: ShortArray, val at: Long, val score: Float)

    private val ring = ArrayDeque<Frame>()

    // The sound the model is scoring now (score at or above the floor), if any.
    private var eventPeak = 0f
    private var eventPeakAt = 0L
    private var eventLastHighAt = 0L

    private class Pending(val pcm: ShortArray, val score: Float, val peakAt: Long)
    private var nearMiss: Pending? = null

    // Scores are ignored until then: the rest of the word that just woke the device.
    private var quietUntil = 0L

    // A wake whose clip is taken once [AFTER_MS] more audio has come in.
    private var wakeAt: Long? = null
    private var wakeScore = 0f
    private var missForWake: Pending? = null

    private class Take(val startAt: Long, val endAt: Long, val prompt: String) {
        val frames = ArrayList<ShortArray>()
        var peak = 0f
        var loudest = 0
    }
    private var take: Take? = null

    /** A guided take is running. */
    val taking: Boolean get() = take != null

    /**
     * Starts a guided take that keeps the next [durationMs] of audio. It is handed out by
     * [onFrame] when it ends, or dropped as silent ([TakeResult.silent]).
     */
    fun startTake(now: Long, durationMs: Long, prompt: String) {
        take = Take(now, now + durationMs, prompt)
    }

    fun cancelTake() { take = null }

    /** What one finished take came to; [clip] is null when it was silent. */
    class TakeResult(val clip: WakeClip?, val peakScore: Float) {
        val silent: Boolean get() = clip == null
    }

    /** What [onFrame] hands out. */
    class Output(val clips: List<WakeClip>, val take: TakeResult?)

    /**
     * One frame of audio at [now] with the model's [score] for it (0 when none). [catchMisses]:
     * the near-miss catcher is on. Returns any clips ready to keep and a finished take.
     */
    fun onFrame(pcm: ShortArray, now: Long, score: Float, threshold: Float, catchMisses: Boolean): Output {
        ring.addLast(Frame(pcm, now, score))
        val ringStart = now - RING_MS
        while (ring.isNotEmpty() && ring.first().at < ringStart) ring.removeFirst()

        val clips = ArrayList<WakeClip>(2)
        var finished: TakeResult? = null

        take?.let { t ->
            t.frames.add(pcm)
            if (score > t.peak) t.peak = score
            t.loudest = maxOf(t.loudest, peakAbs(pcm))
            if (now >= t.endAt) {
                take = null
                val loudEnough = t.loudest >= SPEECH_PEAK
                val clip = if (loudEnough) WakeClip(
                    WakeClip.Kind.GUIDED, concat(t.frames), t.peak, threshold, t.startAt, t.prompt,
                ) else null
                finished = TakeResult(clip, t.peak)
            }
        }

        if (!catchMisses) {
            resetMissState()
            return Output(clips, finished)
        }

        // A wake waiting for the audio after it.
        wakeAt?.let { w ->
            if (now - w >= AFTER_MS) {
                missForWake?.let { m ->
                    clips += WakeClip(WakeClip.Kind.MISSED, m.pcm, m.score, threshold, m.peakAt - BEFORE_MS)
                }
                clips += WakeClip(WakeClip.Kind.HEARD, window(w), wakeScore, threshold, w - BEFORE_MS)
                wakeAt = null
                missForWake = null
            }
        }

        // Follow the sound being scored; when it has ended without waking the device, it is a near
        // miss. (Its single scores may pass the threshold: the device wakes on their running mean.)
        if (now < quietUntil) {
            eventPeak = 0f
        } else if (score >= nearMissFloor) {
            if (eventPeak == 0f || score > eventPeak) {
                eventPeak = score
                eventPeakAt = now
            }
            eventLastHighAt = now
        } else if (eventPeak > 0f && now - eventLastHighAt >= EVENT_END_MS && now - eventPeakAt >= AFTER_MS) {
            // The newest near miss replaces an older one.
            nearMiss = Pending(window(eventPeakAt), eventPeak, eventPeakAt)
            eventPeak = 0f
        }
        nearMiss?.let { if (now - it.peakAt > PAIR_WINDOW_MS) nearMiss = null }
        return Output(clips, finished)
    }

    /**
     * The device woke on the wake word at [now] with [score]. If a near miss came shortly before,
     * the miss and this wake are handed out by [onFrame] once the audio after the wake is in.
     */
    fun onWake(now: Long, score: Float, catchMisses: Boolean) {
        val miss = nearMiss
        nearMiss = null
        eventPeak = 0f // this sound woke the device: it is no near miss
        quietUntil = now + WAKE_QUIET_MS
        if (!catchMisses || miss == null) return
        val gap = now - miss.peakAt
        if (gap < MIN_GAP_MS || gap > PAIR_WINDOW_MS) return
        wakeAt = now
        wakeScore = score
        missForWake = miss
    }

    /** Forgets everything kept (the microphone stopped, or catching was turned off). */
    fun clear() {
        ring.clear()
        take = null
        resetMissState()
    }

    private fun resetMissState() {
        eventPeak = 0f
        quietUntil = 0L
        nearMiss = null
        wakeAt = null
        missForWake = null
    }

    /** The audio from [BEFORE_MS] before [at] to [AFTER_MS] after it, from what the ring holds. */
    private fun window(at: Long): ShortArray =
        concat(ring.filter { it.at >= at - BEFORE_MS && it.at <= at + AFTER_MS }.map { it.pcm })

    companion object {
        /** How much audio is kept in memory. */
        const val RING_MS = 4_000L
        /** A clip around a score peak: this much before it... */
        const val BEFORE_MS = 2_000L
        /** ...and this much after it. */
        const val AFTER_MS = 600L
        /** A sound counts as over once the score has stayed under the floor this long. */
        const val EVENT_END_MS = 300L
        /** A miss followed by a wake within this long is kept. */
        const val PAIR_WINDOW_MS = 8_000L
        /** Scores this soon after a wake are the rest of that word. */
        const val WAKE_QUIET_MS = 1_500L
        /** A "wake" this soon after the miss's peak is the same sound, not a second try. */
        const val MIN_GAP_MS = 700L
        /** The lowest peak score that counts as a near miss. */
        const val NEAR_MISS_FLOOR = 0.5f
        /** A take whose loudest sample is under this (about -42 dBFS) is silent. */
        const val SPEECH_PEAK = 260

        fun concat(frames: List<ShortArray>): ShortArray {
            val out = ShortArray(frames.sumOf { it.size })
            var i = 0
            for (f in frames) { System.arraycopy(f, 0, out, i, f.size); i += f.size }
            return out
        }

        fun peakAbs(pcm: ShortArray): Int {
            var m = 0
            for (s in pcm) { val a = if (s.toInt() < 0) -s.toInt() else s.toInt(); if (a > m) m = a }
            return m
        }
    }
}
