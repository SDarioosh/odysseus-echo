package com.msp1974.vacompanion.odysseus.home.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTabsTest {
    @Test
    fun nowPlayingRoomsLibrarySearch() {
        assertEquals(listOf(MusicTabs.NOW_PLAYING, MusicTabs.ROOMS, MusicTabs.LIBRARY, MusicTabs.SEARCH), MusicTabs.all.map { it.id })
        assertEquals(listOf("Playing", "Rooms", "Library", "Search"), MusicTabs.all.map { it.label })
    }

    @Test
    fun idsAreUnique() {
        assertEquals(MusicTabs.all.size, MusicTabs.all.map { it.id }.toSet().size)
    }

    @Test
    fun defaultIsNowPlaying() {
        assertEquals(MusicTabs.NOW_PLAYING, MusicTabs.selectedId)
        assertEquals(MusicTabs.NOW_PLAYING, MusicTabs.selected.id)
    }

    @Test
    fun unknownIdFallsBackToTheFirstTab() {
        val ids = listOf("a", "b", "c")
        assertEquals(1, MusicTabs.indexOf("b", ids))
        assertEquals(0, MusicTabs.indexOf("library", ids))
        assertEquals(0, MusicTabs.indexOf(null, ids))
    }
}
