package com.msp1974.vacompanion.odysseus.home.music

import com.msp1974.vacompanion.odysseus.ha.HaException
import com.msp1974.vacompanion.odysseus.music.How
import com.msp1974.vacompanion.odysseus.music.MusicLocked
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.music.SelfMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MusicActionsTest {
    @Test
    fun lockedSaysOnlyLocked() {
        assertEquals("Locked", musicActionMessage("Pause", MusicLocked()))
    }

    @Test
    fun networkErrorsSayHomeAssistantOffline() {
        assertEquals("Home Assistant offline", musicActionMessage("Move", IOException("connect timed out")))
    }

    @Test
    fun refusedTokenAsksToSignInAgain() {
        assertEquals("Volume: Home Assistant refused (sign in again)", musicActionMessage("Volume", HaException("x", 401)))
        assertEquals("Volume: Home Assistant refused (sign in again)", musicActionMessage("Volume", HaException("x", 403)))
    }

    @Test
    fun serverErrorsNameTheCode() {
        assertEquals("Group: Home Assistant error (HTTP 502)", musicActionMessage("Group", HaException("bad gateway", 502)))
    }

    @Test
    fun otherErrorsGiveAShortReason() {
        assertEquals("Move: Music Assistant isn't set up in Home Assistant",
            musicActionMessage("Move", HaException("Music Assistant isn't set up in Home Assistant")))
        val long = musicActionMessage("Play", IllegalStateException("x".repeat(200)))
        assertTrue(long.endsWith("…"))
        assertTrue(long.length <= "Play: ".length + 60)
        assertEquals("Play: IllegalStateException", musicActionMessage("Play", IllegalStateException()))
        assertEquals("boom", musicActionMessage("", RuntimeException("boom")))
    }

    @Test
    fun wallClockIsToldFromElapsedRealtime() {
        assertTrue(isWallClock(1_793_000_000_000L)) // 2026
        assertFalse(isWallClock(86_400_000L * 400)) // 400 days of uptime
        assertFalse(isWallClock(0))
    }

    @Test
    fun artCacheKeepsTheMostRecentlyUsed() {
        val cache = ArtCache<String>(2)
        cache.put("a", "A"); cache.put("b", "B")
        assertEquals("A", cache.get("a")) // a is now the newest
        cache.put("c", "C")
        assertNull(cache.get("b"))
        assertEquals(listOf("a", "c"), cache.keys())
    }

    private val rooms = listOf(
        Room("media_player.kitchen", "Kitchen", "Kitchen", true, false, emptyList(), true),
    )

    @Test
    fun selfMatchTextSaysHow() {
        assertEquals("No player found for this device", selfMatchText(null, rooms))
        assertEquals("Kitchen (found by its player id)", selfMatchText(SelfMatch("media_player.kitchen", How.ID), rooms))
        assertEquals("Kitchen? (a guess from this device's area)", selfMatchText(SelfMatch("media_player.kitchen", How.AREA), rooms))
        assertEquals("media_player.x (chosen here)", selfMatchText(SelfMatch("media_player.x", How.OVERRIDE), rooms))
    }
}
