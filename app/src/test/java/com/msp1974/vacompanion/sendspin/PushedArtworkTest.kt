package com.msp1974.vacompanion.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushedArtworkTest {

    @Test
    fun imageShowsOnlyAtItsDisplayTime() {
        val a = PushedArtwork<String>()
        a.onImage("cover1")
        assertNull(a.shown)
        assertTrue(a.onDisplay())
        assertEquals("cover1", a.shown)
        assertFalse(a.onDisplay())
    }

    @Test
    fun clearWhileStreamingMeansTheTrackHasNoArt() {
        val a = PushedArtwork<String>()
        a.onImage("cover1"); a.onDisplay()
        assertTrue(a.onClear(streaming = true, track = "B"))
        assertNull(a.shown)
    }

    @Test
    fun pauseKeepsTheArtForTheSameTrack() {
        val a = PushedArtwork<String>()
        a.onImage("cover1"); a.onDisplay()
        assertFalse(a.onClear(streaming = false, track = "A"))
        assertEquals("cover1", a.shown)
        assertFalse(a.onTrack("A"))
        assertEquals("cover1", a.shown)
    }

    @Test
    fun afterAPauseAnotherTrackWithoutNewArtDropsIt() {
        val a = PushedArtwork<String>()
        a.onImage("cover1"); a.onDisplay()
        a.onClear(streaming = false, track = "A")
        assertTrue(a.onTrack("B"))
        assertNull(a.shown)
    }

    @Test
    fun newArtAfterAPauseReplacesIt() {
        val a = PushedArtwork<String>()
        a.onImage("cover1"); a.onDisplay()
        a.onClear(streaming = false, track = "A")
        a.onImage("cover2"); a.onDisplay()
        assertFalse(a.onTrack("B"))
        assertEquals("cover2", a.shown)
    }

    @Test
    fun trackChangesWhilePlayingKeepTheArtUntilTheServerSaysOtherwise() {
        // The server doesn't resend an unchanged image (same album), so a new track alone keeps it.
        val a = PushedArtwork<String>()
        a.onImage("album"); a.onDisplay()
        assertFalse(a.onTrack("B"))
        assertEquals("album", a.shown)
    }

    @Test
    fun clearDropsAnImageNotYetShown() {
        val a = PushedArtwork<String>()
        a.onImage("cover1")
        a.onClear(streaming = true, track = "A")
        assertFalse(a.onDisplay())
        assertNull(a.shown)
    }

    @Test
    fun resetForgetsEverything() {
        val a = PushedArtwork<String>()
        a.onImage("cover1"); a.onDisplay(); a.onImage("cover2")
        a.reset()
        assertNull(a.shown)
        assertFalse(a.onDisplay())
    }
}
