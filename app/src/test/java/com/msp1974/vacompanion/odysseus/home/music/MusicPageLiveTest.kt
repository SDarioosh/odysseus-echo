package com.msp1974.vacompanion.odysseus.home.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPageLiveTest {
    private fun live(destination: Boolean, position: Float?, index: Int = 1) =
        MusicPageLive.isLive(destination, position, index)

    @Test fun idleBesideThePageShown() {
        // The clock (0) shows; the Music page (1) is composed beside it, out of sight.
        assertFalse(live(false, 0f))
        // Today (1) shows; the Music page is 2.
        assertFalse(live(false, 1f, index = 2))
        // Shown on the page after it (a later tab page) too.
        assertFalse(live(false, 2f))
    }

    @Test fun liveWhileShownOrComingIn() {
        assertTrue(live(true, 1f))
        assertTrue(live(true, 0f)) // goTo: going there, the strip hasn't moved yet
        assertTrue(live(false, 0.05f)) // a swipe brings it in
        assertTrue(live(false, 1.5f)) // half of it while swiping on to the next page
        assertFalse(live(false, 0.01f))
    }

    @Test fun liveOutsideHomeHost() {
        assertTrue(live(false, null))
        assertTrue(live(false, 0f, index = -1))
    }
}
