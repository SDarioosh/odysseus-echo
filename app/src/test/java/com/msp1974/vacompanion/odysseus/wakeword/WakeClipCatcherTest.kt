package com.msp1974.vacompanion.odysseus.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeClipCatcherTest {

    private val frameMs = 80L
    private val threshold = 0.92f
    private val speech = ShortArray(1280) { 3000 }
    private val quiet = ShortArray(1280)

    /** Feeds frames with [scores], one per 80 ms from [from]; returns the clips handed out and the time after. */
    private fun feed(
        c: WakeClipCatcher, from: Long, scores: List<Float>, catching: Boolean = true,
        pcm: ShortArray = speech,
    ): Pair<List<WakeClip>, Long> {
        var t = from
        val clips = mutableListOf<WakeClip>()
        for (s in scores) {
            clips += c.onFrame(pcm, t, s, threshold, catching).clips
            t += frameMs
        }
        return clips to t
    }

    private fun silence(n: Int) = List(n) { 0f }

    @Test
    fun nearMissThenWakeKeepsBoth() {
        val c = WakeClipCatcher()
        var (clips, t) = feed(c, 0, silence(30) + listOf(0.55f, 0.7f, 0.6f) + silence(15))
        assertTrue(clips.isEmpty())
        val wakeAt = t
        c.onWake(wakeAt, 0.97f, catchMisses = true)
        val (after, _) = feed(c, t, silence(10))
        clips = after
        assertEquals(listOf(WakeClip.Kind.MISSED, WakeClip.Kind.HEARD), clips.map { it.kind })
        assertEquals(0.7f, clips[0].score)
        assertEquals(0.97f, clips[1].score)
        // About 2.6 s each: 2 s before the peak (or the wake) and 0.6 s after.
        for (clip in clips) assertTrue(clip.pcm.size in 16_000 * 2..16_000 * 3)
        assertEquals(threshold, clips[0].threshold)
    }

    @Test
    fun aSingleScorePastTheThresholdWithoutAWakeIsStillAMiss() {
        val c = WakeClipCatcher()
        var (_, t) = feed(c, 0, silence(30) + listOf(0.95f, 0.5f) + silence(15))
        c.onWake(t, 0.96f, catchMisses = true)
        val (clips, _) = feed(c, t, silence(10))
        assertEquals(WakeClip.Kind.MISSED, clips.first().kind)
        assertEquals(0.95f, clips.first().score)
    }

    @Test
    fun aWakeWithoutANearMissKeepsNothing() {
        val c = WakeClipCatcher()
        var (_, t) = feed(c, 0, silence(30) + listOf(0.95f, 0.98f))
        c.onWake(t, 0.98f, catchMisses = true)
        val (clips, _) = feed(c, t, listOf(0.9f, 0.6f) + silence(30))
        assertTrue(clips.isEmpty())
    }

    @Test
    fun theRestOfAWakingWordIsNoNearMiss() {
        val c = WakeClipCatcher()
        var (_, t) = feed(c, 0, silence(30) + listOf(0.95f))
        c.onWake(t, 0.95f, catchMisses = true)
        // Residual scores right after the wake, then a second wake 3 s later.
        var r = feed(c, t, listOf(0.8f, 0.6f, 0.4f) + silence(35))
        t = r.second
        c.onWake(t, 0.97f, catchMisses = true)
        r = feed(c, t, silence(10))
        assertTrue(r.first.isEmpty())
    }

    @Test
    fun aNearMissLongBeforeTheWakeIsForgotten() {
        val c = WakeClipCatcher()
        var (_, t) = feed(c, 0, silence(30) + listOf(0.6f) + silence(110)) // ~9 s later
        c.onWake(t, 0.97f, catchMisses = true)
        val (clips, _) = feed(c, t, silence(10))
        assertTrue(clips.isEmpty())
    }

    @Test
    fun scoresUnderTheFloorAreNoNearMiss() {
        val c = WakeClipCatcher()
        var (_, t) = feed(c, 0, silence(30) + listOf(0.3f, 0.45f) + silence(15))
        c.onWake(t, 0.97f, catchMisses = true)
        val (clips, _) = feed(c, t, silence(10))
        assertTrue(clips.isEmpty())
    }

    @Test
    fun nothingIsKeptWhileCatchingIsOff() {
        val c = WakeClipCatcher()
        var (_, t) = feed(c, 0, silence(30) + listOf(0.7f) + silence(15), catching = false)
        c.onWake(t, 0.97f, catchMisses = false)
        val (clips, _) = feed(c, t, silence(10), catching = false)
        assertTrue(clips.isEmpty())
    }

    @Test
    fun aGuidedTakeKeepsItsWholeWindowWithItsPeak() {
        val c = WakeClipCatcher()
        c.startTake(1_000, 3_000, "Quietly")
        assertTrue(c.taking)
        var result: WakeClipCatcher.TakeResult? = null
        var t = 1_000L
        val scores = silence(10) + listOf(0.5f, 0.97f, 0.6f) + silence(30)
        for (s in scores) {
            c.onFrame(speech, t, s, threshold, catchMisses = false).take?.let { result = it }
            t += frameMs
            if (result != null) break
        }
        assertNotNull(result)
        val r = result!!
        assertFalse(c.taking)
        assertFalse(r.silent)
        assertEquals(0.97f, r.peakScore)
        val clip = r.clip!!
        assertEquals(WakeClip.Kind.GUIDED, clip.kind)
        assertEquals("Quietly", clip.prompt)
        assertEquals(1_000L, clip.recordedAt)
        assertTrue(clip.pcm.size in 16_000 * 3..16_000 * 3 + 1280 * 2)
    }

    @Test
    fun aSilentTakeIsNotKept() {
        val c = WakeClipCatcher()
        c.startTake(0, 1_000, "Normal")
        var result: WakeClipCatcher.TakeResult? = null
        var t = 0L
        while (result == null) {
            result = c.onFrame(quiet, t, 0f, threshold, catchMisses = false).take
            t += frameMs
        }
        assertTrue(result!!.silent)
        assertNull(result!!.clip)
    }

    @Test
    fun theRingKeepsOnlyTheLastFourSeconds() {
        val c = WakeClipCatcher()
        // A peak after 10 s of audio: its clip can't reach further back than the ring.
        var (_, t) = feed(c, 0, silence(125) + listOf(0.7f) + silence(15))
        c.onWake(t, 0.97f, catchMisses = true)
        val (clips, _) = feed(c, t, silence(10))
        assertTrue(clips[0].pcm.size <= 16_000 * 4)
    }
}
