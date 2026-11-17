package com.msp1974.vacompanion.odysseus.player

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinPlayerStateTest {
    private val all = setOf("play", "pause", "next", "previous", "shuffle", "unshuffle", "repeat_off", "repeat_all", "repeat_one")
    private val np = SendspinNowPlaying(
        title = "Fictional Song", artist = "The Placeholders", album = "Demo Album",
        artworkUrl = "http://ma.local/art.jpg", durationMs = 180_500, positionMs = 42_000, positionAtMs = 7_000,
        active = true, playing = true, commands = all, shuffle = true, repeat = "all",
    )

    @Test fun mapsFields() {
        val s = np.toPlayerState(wallMinusElapsedMs = 1_793_000_000_000L)
        assertEquals("Fictional Song", s.title)
        assertEquals("The Placeholders", s.artist)
        assertEquals("Demo Album", s.album)
        assertEquals("http://ma.local/art.jpg", s.artUrl)
        assertNull(s.artwork)
        assertTrue(s.playing)
        assertEquals(180.5, s.durationS!!, 1e-9)
        assertEquals(42.0, s.positionS!!, 1e-9)
        assertEquals(1_793_000_007_000L, s.positionAtMs) // moved to the wall clock (PlayerClock)
        assertTrue(s.shuffle)
        assertEquals(Repeat.ALL, s.repeat)
        assertNull(s.volume)
        assertFalse(s.muted)
        assertEquals(setOf(Control.PLAY, Control.PAUSE, Control.NEXT, Control.PREVIOUS, Control.SHUFFLE, Control.REPEAT), s.can)
    }

    @Test fun theOffsetIsKeptSoTheSameStateConvertsTheSame() {
        val o = PlayerClock.wallMinusElapsed()
        assertEquals(o, PlayerClock.wallMinusElapsed())
        assertEquals(np.toPlayerState(), np.toPlayerState())
        assertEquals(7_000L, PlayerClock.wallToElapsed(PlayerClock.elapsedToWall(7_000L)))
    }

    @Test fun blanksAndNoDuration() {
        val s = SendspinNowPlaying().toPlayerState()
        assertNull(s.title); assertNull(s.artist); assertNull(s.album)
        assertNull(s.durationS)
        assertEquals(Repeat.OFF, s.repeat)
        assertTrue(s.can.isEmpty())
    }

    @Test fun speedBecomesRate() {
        assertEquals(1.0, np.toPlayerState().rate, 1e-9) // speed not said
        assertEquals(1.0, np.copy(speed = 0).toPlayerState().rate, 1e-9)
        val fast = np.copy(speed = 1_500).toPlayerState(wallMinusElapsedMs = 0L) // both on one clock here
        assertEquals(1.5, fast.rate, 1e-9)
        // Matches Sendspin's own position: 2 s at 1.5x from 42 s.
        assertEquals(np.copy(speed = 1_500).positionNow(9_000) / 1000.0, fast.positionNow(9_000)!!, 1e-9)
        assertEquals(45.0, fast.positionNow(9_000)!!, 1e-9)
    }

    @Test fun playingNeedsActiveStream() {
        assertFalse(np.copy(active = false).toPlayerState().playing)
        assertFalse(np.copy(playing = false).toPlayerState().playing)
        assertEquals(Repeat.ONE, np.copy(repeat = "one").toPlayerState().repeat)
    }

    @Test fun onlyOfferedCommandsBecomeControls() {
        val s = np.copy(commands = setOf("pause", "repeat_one", "volume", "mute", "switch")).toPlayerState()
        assertEquals(setOf(Control.PAUSE, Control.REPEAT), s.can)
        assertEquals(setOf(Control.SHUFFLE), np.copy(commands = setOf("unshuffle")).toPlayerState().can)
    }

    @Test fun clockCommandsRoundTrip() {
        // The clock's buttons sent exactly these strings; each comes back from its Control.
        for (command in listOf("play", "pause", "next", "previous")) {
            val control = Control.valueOf(command.uppercase())
            assertTrue(control in np.toPlayerState().can)
            assertEquals(command, np.commandFor(control))
        }
    }

    @Test fun notOfferedGivesNull() {
        val few = np.copy(commands = setOf("play"))
        assertNull(few.commandFor(Control.PAUSE))
        assertNull(few.commandFor(Control.NEXT))
        for (c in listOf(Control.SEEK, Control.VOLUME, Control.MUTE, Control.GROUP)) assertNull(np.commandFor(c, 0.5f))
    }

    @Test fun shuffleTogglesOrTakesValue() {
        assertEquals("unshuffle", np.commandFor(Control.SHUFFLE))
        assertEquals("shuffle", np.copy(shuffle = false).commandFor(Control.SHUFFLE))
        assertEquals("shuffle", np.commandFor(Control.SHUFFLE, true))
        assertEquals("unshuffle", np.copy(shuffle = false).commandFor(Control.SHUFFLE, false))
    }

    @Test fun repeatCyclesOverOfferedModes() {
        assertEquals("repeat_one", np.commandFor(Control.REPEAT))                     // all -> one
        assertEquals("repeat_off", np.copy(repeat = "one").commandFor(Control.REPEAT))
        assertEquals("repeat_all", np.copy(repeat = "off").commandFor(Control.REPEAT))
        assertEquals("repeat_off", np.copy(commands = setOf("repeat_off")).commandFor(Control.REPEAT)) // skips missing
        assertNull(np.copy(commands = setOf("play")).commandFor(Control.REPEAT))
        assertEquals("repeat_one", np.copy(repeat = "off").commandFor(Control.REPEAT, Repeat.ONE))
    }
}
