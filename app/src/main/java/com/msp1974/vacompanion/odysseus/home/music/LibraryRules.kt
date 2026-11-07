package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the pure rules of the Library and Search tabs (docs/odysseus/music.md, "Library",
// "Search"): sort choices, paging, when to load more, the search query and its grouping. No
// Compose, no Android; unit-tested (LibraryRulesTest).

import com.msp1974.vacompanion.odysseus.music.LibraryOrder
import com.msp1974.vacompanion.odysseus.music.LibraryPage
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind

/** A sort the Library offers: its [label] and the backend's [order]. */
data class LibrarySort(val label: String, val order: LibraryOrder)

/** What one Library list shows: a section, its sort and the favourites filter. */
data class LibraryQuery(val kind: MediaKind, val order: LibraryOrder, val favoritesOnly: Boolean)

/**
 * A Library list as loaded so far. [nextOffset] is where the next page starts (MA's offset counts
 * every item it returned, also ones the device couldn't read); [hasMore] while the last page was
 * full (MA gives no total); [error] the last failure (loading stops until [LibraryRules.retry]).
 */
data class LibraryList(
    val items: List<MediaItem> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** Loaded to the end and empty. */
    val empty: Boolean get() = items.isEmpty() && !hasMore && !loading && error == null
}

/** A list's grid: cards or rows, cards to a line, rows to a line (see ItemLayout). */
data class LibraryGrid(val cards: Boolean, val cardCols: Int, val rowCols: Int)

object LibraryRules {
    /** Cards (a grid of covers) on screens at least this tall and wide (dp); rows below (the Echo). */
    const val CARDS_FROM_DP = 560f

    /** Card cells are at least this wide (dp). */
    const val CARD_MIN_DP = 180f

    /** Gap between cells (dp). */
    const val GAP_DP = 8f

    /** Rows go two to a line from this width (dp). */
    const val TWO_ROWS_FROM_DP = 580f

    /**
     * The grid for a list area [widthDp] (inside its padding) x [heightDp]: cards on tablets, as
     * many as fit at [CARD_MIN_DP]; rows on the Echo; rows two to a line from [TWO_ROWS_FROM_DP].
     */
    fun grid(widthDp: Float, heightDp: Float): LibraryGrid {
        val cards = heightDp >= CARDS_FROM_DP && widthDp >= CARDS_FROM_DP
        val cardCols = if (cards) maxOf(1, ((widthDp + GAP_DP) / (CARD_MIN_DP + GAP_DP)).toInt()) else 1
        val rowCols = if (widthDp >= TWO_ROWS_FROM_DP) 2 else 1
        return LibraryGrid(cards, cardCols, rowCols)
    }

    /** Items per page. */
    const val PAGE = 50

    /** Load the next page once the last visible item is this close to the end. */
    const val PREFETCH = 15

    /** Search waits this long after the last keystroke. */
    const val SEARCH_DEBOUNCE_MS = 400L

    /** Search asks nothing below this many characters. */
    const val SEARCH_MIN_CHARS = 2

    /** Results per kind. */
    const val SEARCH_LIMIT = 10

    /** The sorts, the default first (Recent: what was played last). */
    val sorts: List<LibrarySort> = listOf(
        LibrarySort("Recent", LibraryOrder.RECENT),
        LibrarySort("Name", LibraryOrder.NAME),
        LibrarySort("Added", LibraryOrder.ADDED),
    )

    /** The label of [order] ("Recent" for one the menu doesn't offer). */
    fun sortLabel(order: LibraryOrder): String = sorts.firstOrNull { it.order == order }?.label ?: sorts.first().label

    /** The list once a load started: marked loading, the old error cleared. */
    fun started(list: LibraryList): LibraryList = list.copy(loading = true, error = null)

    /**
     * [list] with [page] appended (asked for [limit] items at [LibraryList.nextOffset]); items it
     * already holds (same uri) aren't added twice, as MA's order can shift between pages.
     */
    fun append(list: LibraryList, page: LibraryPage, limit: Int = PAGE): LibraryList {
        val seen = list.items.mapTo(HashSet()) { it.uri }
        val added = page.items.filter { seen.add(it.uri) }
        return LibraryList(
            items = list.items + added,
            nextOffset = page.offset + limit,
            hasMore = page.hasMore,
            loading = false,
            error = null,
        )
    }

    /** The list after a failed load: what it had, with [message]. */
    fun failed(list: LibraryList, message: String): LibraryList = list.copy(loading = false, error = message)

    /** Clears the error so loading may go on. */
    fun retry(list: LibraryList): LibraryList = list.copy(error = null)

    /**
     * True when the next page should load: there is more, nothing is loading, the last load didn't
     * fail, and [lastVisible] (index of the last item on screen, -1 for none) is within
     * [PREFETCH] of the end. An empty list that may have more always loads (the first page).
     */
    fun shouldLoadMore(list: LibraryList, lastVisible: Int, prefetch: Int = PREFETCH): Boolean {
        if (!list.hasMore || list.loading || list.error != null) return false
        if (list.items.isEmpty()) return true
        return lastVisible >= list.items.size - 1 - prefetch
    }

    /** The text to search for: trimmed, or null below [SEARCH_MIN_CHARS] characters. */
    fun searchQuery(text: String): String? = text.trim().takeIf { it.length >= SEARCH_MIN_CHARS }

    /** Search kinds in the order the results are shown (the kinds Library has sections for). */
    val searchKinds: List<MediaKind> = listOf(
        MediaKind.ARTIST, MediaKind.ALBUM, MediaKind.TRACK, MediaKind.PLAYLIST, MediaKind.RADIO,
        MediaKind.PODCAST, MediaKind.AUDIOBOOK,
    )

    /** [results] in [searchKinds] order, without kinds that found nothing. */
    fun searchGroups(results: Map<MediaKind, List<MediaItem>>): List<Pair<MediaKind, List<MediaItem>>> =
        searchKinds.mapNotNull { k -> results[k]?.takeIf { it.isNotEmpty() }?.let { k to it } }

    /** Kinds whose tap opens a detail list (the rest open the play menu). */
    fun opensDetail(kind: MediaKind): Boolean = kind == MediaKind.ARTIST || kind == MediaKind.ALBUM
}
