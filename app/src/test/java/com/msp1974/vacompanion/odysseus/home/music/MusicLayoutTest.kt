package com.msp1974.vacompanion.odysseus.home.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLayoutTest {
    @Test
    fun `the Echo Show 5 stays one pane at mdpi and tvdpi`() {
        assertFalse(MusicLayout.twoPanes(960f, 480f))
        assertFalse(MusicLayout.twoPanes(721f, 360f))
    }

    @Test
    fun `tablets in landscape get two panes`() {
        assertTrue(MusicLayout.twoPanes(1280f, 800f))
        assertTrue(MusicLayout.twoPanes(1920f, 1200f))
        assertTrue(MusicLayout.twoPanes(1100f, 600f))
    }

    @Test
    fun `wide but short, or tall but narrow, stays one pane`() {
        assertFalse(MusicLayout.twoPanes(1280f, 599f))
        assertFalse(MusicLayout.twoPanes(1099f, 800f))
        assertFalse(MusicLayout.twoPanes(800f, 1280f)) // a tablet in portrait
    }

    @Test
    fun `the pane shows beside every tab but Playing`() {
        assertFalse(MusicLayout.sidePane(1280f, 800f, MusicTabs.NOW_PLAYING))
        assertTrue(MusicLayout.sidePane(1280f, 800f, MusicTabs.ROOMS))
        assertTrue(MusicLayout.sidePane(1280f, 800f, "library"))
        assertFalse(MusicLayout.sidePane(960f, 480f, MusicTabs.ROOMS))
    }

    @Test
    fun `the pane is a third of the page within 380 to 560 dp`() {
        assertEquals(380f, MusicLayout.paneWidthDp(1100f), 0.01f)
        assertEquals(435.2f, MusicLayout.paneWidthDp(1280f), 0.01f)
        assertEquals(560f, MusicLayout.paneWidthDp(1920f), 0.01f)
    }

    @Test
    fun `room columns beside the pane keep cards readable`() {
        assertEquals(1, MusicLayout.roomColumnsBesidePane(500f))
        assertEquals(2, MusicLayout.roomColumnsBesidePane(757f))
        assertEquals(3, MusicLayout.roomColumnsBesidePane(1272f))
        assertEquals(3, MusicLayout.roomColumnsBesidePane(3000f))
    }
}
