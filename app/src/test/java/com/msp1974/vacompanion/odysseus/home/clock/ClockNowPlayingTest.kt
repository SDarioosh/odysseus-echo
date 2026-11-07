package com.msp1974.vacompanion.odysseus.home.clock

import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockNowPlayingTest {
    private fun track(playing: Boolean) = PlayerState(
        title = "Harbour Lights", artist = "The Fictional Band", album = null, artUrl = null, playing = playing,
        durationS = 200.0, positionS = 10.0, positionAtMs = 0L, shuffle = false, repeat = Repeat.OFF,
        volume = 0.5f, muted = false, can = emptySet(),
    )

    @Test
    fun `a track is drawn as it is`() {
        val now = ClockMusic(track(true), active = true, artwork = null)
        assertSame(now, lingered(now, ClockMusic.NONE))
    }

    @Test
    fun `after it stops the last track lingers, stopped`() {
        val last = ClockMusic(track(true), active = true, artwork = null)
        val shown = lingered(ClockMusic.NONE, last)
        assertEquals("Harbour Lights", shown.state?.title)
        assertFalse(shown.state!!.playing)
        assertFalse(shown.active)
    }

    @Test
    fun `nothing ever played shows nothing`() {
        assertNull(lingered(ClockMusic.NONE, ClockMusic.NONE).state)
    }

    @Test
    fun `a paused track is still drawn (dimmed by the element)`() {
        val paused = ClockMusic(track(false), active = false, artwork = null)
        val shown = lingered(paused, ClockMusic.NONE)
        assertTrue(shown.state != null)
        assertFalse(shown.active)
    }
}
