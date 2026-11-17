package com.msp1974.vacompanion.odysseus.home.music

import com.msp1974.vacompanion.odysseus.music.LibraryOrder
import com.msp1974.vacompanion.odysseus.music.LibraryPage
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRulesTest {
    private fun item(n: Int, kind: MediaKind = MediaKind.ALBUM) = MediaItem("library://album/$n", kind, "Album $n", "Fictional", null)
    private fun page(from: Int, count: Int, offset: Int, hasMore: Boolean) =
        LibraryPage((from until from + count).map { item(it) }, offset, hasMore)

    @Test
    fun recentIsTheDefaultSort() {
        assertEquals(listOf(LibraryOrder.RECENT, LibraryOrder.NAME, LibraryOrder.ADDED), LibraryRules.sorts.map { it.order })
        assertEquals("Name", LibraryRules.sortLabel(LibraryOrder.NAME))
        assertEquals("Recent", LibraryRules.sortLabel(LibraryOrder.RANDOM))
    }

    @Test
    fun pagesAppendAndTheNextOffsetCountsWhatMaReturned() {
        var list = LibraryList()
        assertTrue(LibraryRules.shouldLoadMore(list, -1))
        list = LibraryRules.started(list)
        assertFalse(LibraryRules.shouldLoadMore(list, -1))
        // 50 asked, 48 readable: the next page still starts at 50.
        list = LibraryRules.append(list, page(0, 48, 0, hasMore = true), 50)
        assertEquals(48, list.items.size)
        assertEquals(50, list.nextOffset)
        assertTrue(list.hasMore)
        assertFalse(list.loading)
        // A shifted order repeats an item: kept once.
        list = LibraryRules.append(list, page(47, 3, 50, hasMore = false), 50)
        assertEquals(50, list.items.size)
        assertEquals(100, list.nextOffset)
        assertFalse(list.hasMore)
        assertFalse(LibraryRules.shouldLoadMore(list, 49))
    }

    @Test
    fun loadsMoreNearTheEndOnly() {
        val list = LibraryRules.append(LibraryList(), page(0, 50, 0, hasMore = true), 50)
        assertFalse(LibraryRules.shouldLoadMore(list, 10))
        assertFalse(LibraryRules.shouldLoadMore(list, 33))
        assertTrue(LibraryRules.shouldLoadMore(list, 34))
        assertTrue(LibraryRules.shouldLoadMore(list, 49))
    }

    @Test
    fun aFailureStopsLoadingUntilRetried() {
        var list = LibraryRules.append(LibraryList(), page(0, 50, 0, hasMore = true), 50)
        list = LibraryRules.failed(LibraryRules.started(list), "Home Assistant offline")
        assertEquals(50, list.items.size)
        assertEquals("Home Assistant offline", list.error)
        assertFalse(LibraryRules.shouldLoadMore(list, 49))
        list = LibraryRules.retry(list)
        assertTrue(LibraryRules.shouldLoadMore(list, 49))
    }

    @Test
    fun emptyOnlyOnceLoadedToTheEnd() {
        assertFalse(LibraryList().empty)
        assertTrue(LibraryRules.append(LibraryList(), LibraryPage(emptyList(), 0, false)).empty)
        assertFalse(LibraryRules.failed(LibraryList(hasMore = false), "x").empty)
    }

    @Test
    fun searchQueryNeedsTwoCharacters() {
        assertNull(LibraryRules.searchQuery(""))
        assertNull(LibraryRules.searchQuery("  a "))
        assertEquals("ab", LibraryRules.searchQuery(" ab "))
    }

    @Test
    fun searchGroupsInOrderWithoutEmptyKinds() {
        val results = mapOf(
            MediaKind.TRACK to listOf(item(1, MediaKind.TRACK)),
            MediaKind.PLAYLIST to emptyList(),
            MediaKind.ARTIST to listOf(item(2, MediaKind.ARTIST)),
        )
        assertEquals(listOf(MediaKind.ARTIST, MediaKind.TRACK), LibraryRules.searchGroups(results).map { it.first })
    }

    @Test
    fun onlyArtistsAndAlbumsOpen() {
        assertEquals(setOf(MediaKind.ARTIST, MediaKind.ALBUM), MediaKind.entries.filter { LibraryRules.opensDetail(it) }.toSet())
    }

    @Test
    fun everyKindHasASectionOnce() {
        assertEquals(MediaKind.entries.toSet(), LibrarySections.all.map { it.kind }.toSet())
        assertEquals(LibrarySections.all.size, LibrarySections.all.map { it.kind }.toSet().size)
        assertEquals(MediaKind.PLAYLIST, LibrarySections.all.first().kind)
    }

    @Test
    fun gridRowsOnTheEchoCardsOnTablets() {
        // Echo Show 5 at mdpi and tvdpi (rail and padding taken off): rows, two to a line.
        assertEquals(LibraryGrid(cards = false, cardCols = 1, rowCols = 2), LibraryRules.grid(848f, 480f))
        assertEquals(LibraryGrid(cards = false, cardCols = 1, rowCols = 2), LibraryRules.grid(609f, 360f))
        assertEquals(LibraryGrid(cards = false, cardCols = 1, rowCols = 1), LibraryRules.grid(400f, 700f))
        // A 1280x800 tablet: six cards to a line; half of it (two panes): three.
        assertEquals(LibraryGrid(cards = true, cardCols = 6, rowCols = 2), LibraryRules.grid(1168f, 800f))
        assertEquals(LibraryGrid(cards = true, cardCols = 3, rowCols = 2), LibraryRules.grid(600f, 800f))
    }
}
