package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page's "Library" tab (docs/odysseus/music.md, "Library"): Music
// Assistant's library by section (LibrarySections), 50 at a time while scrolling, sorted Recent
// (default), Name or Added, optionally favourites only. Rows on the Echo, cards on tablets;
// vertical scrolling only (the home screen owns horizontal drags). Artists and albums open a
// detail view; everything has a play menu. The paging rules are in LibraryRules (pure, tested).

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.odysseus.music.LibraryOrder
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Library tab's state, process-wide Compose state (kept while the page is away, not across
 * restarts): the section, sort and filter, each list loaded so far with its scroll position, and
 * the open detail views.
 */
object LibraryState {
    /** A list loaded longer ago than this starts again when it is shown. */
    const val STALE_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var kind by mutableStateOf(LibrarySections.all.first().kind)
    var order by mutableStateOf(LibraryRules.sorts.first().order)
    var favoritesOnly by mutableStateOf(false)

    /** Detail views opened (artist → album …); the last one shows. */
    val stack = mutableStateListOf<MediaItem>()

    private val lists = mutableStateMapOf<LibraryQuery, LibraryList>()
    private val loadedAt = HashMap<LibraryQuery, Long>()
    private val grids = HashMap<LibraryQuery, LazyGridState>()

    val query: LibraryQuery get() = LibraryQuery(kind, order, favoritesOnly)

    fun list(q: LibraryQuery): LibraryList = lists[q] ?: LibraryList()

    /** The scroll position of [q]'s list (kept per list). */
    fun grid(q: LibraryQuery): LazyGridState = synchronized(grids) { grids.getOrPut(q) { LazyGridState() } }

    /** Starts [q] again when it was loaded longer than [STALE_MS] ago. */
    fun refreshIfStale(q: LibraryQuery, nowMs: Long = System.currentTimeMillis()) {
        val at = synchronized(loadedAt) { loadedAt[q] } ?: return
        if (nowMs - at < STALE_MS || list(q).loading) return
        reset(q)
    }

    /** Forgets [q]'s list (it loads again from the start). */
    fun reset(q: LibraryQuery) {
        lists.remove(q)
        synchronized(loadedAt) { loadedAt.remove(q) }
        synchronized(grids) { grids.remove(q) }
    }

    /** Loads [q]'s next page (nothing while one loads). */
    fun loadMore(q: LibraryQuery) {
        val current = list(q)
        if (current.loading) return
        val backend = MusicRepository.backend
        if (backend == null) {
            lists[q] = LibraryRules.failed(current, MUSIC_HA_OFFLINE)
            return
        }
        lists[q] = LibraryRules.started(current)
        scope.launch {
            try {
                val page = backend.library(q.kind, q.order, q.favoritesOnly, current.nextOffset, LibraryRules.PAGE)
                lists[q] = LibraryRules.append(list(q), page, LibraryRules.PAGE)
                if (current.nextOffset == 0) synchronized(loadedAt) { loadedAt[q] = System.currentTimeMillis() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Music: library %s failed: %s", q.kind, e.message)
                lists[q] = LibraryRules.failed(list(q), musicActionMessage("", e))
            }
        }
    }

    /** Clears the error of [q] (the list then loads on). */
    fun retry(q: LibraryQuery) {
        lists[q] = LibraryRules.retry(list(q))
    }
}

@Composable
fun LibraryTab(modifier: Modifier = Modifier) {
    var menuItem by remember { mutableStateOf<MediaItem?>(null) }
    val open = { item: MediaItem -> LibraryState.stack.add(item); Unit }
    val menu = { item: MediaItem -> menuItem = item }

    val detail = LibraryState.stack.lastOrNull()
    if (detail != null) {
        MediaDetailView(
            detail,
            onBack = { LibraryState.stack.removeAt(LibraryState.stack.lastIndex) },
            onOpen = open, onMenu = menu, modifier = modifier,
        )
    } else {
        Column(modifier) {
            LibraryBar()
            LibraryList(LibraryState.query, open, menu, Modifier.weight(1f))
        }
    }
    menuItem?.let { PlayMenuDialog(it) { menuItem = null } }
}

@Composable
private fun LibraryBar() {
    var sections by remember { mutableStateOf(false) }
    var sorts by remember { mutableStateOf(false) }
    val section = LibrarySections.of(LibraryState.kind)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Box {
            BarChip(section.label, section.icon, dropdown = true) { sections = true }
            DropdownMenu(expanded = sections, onDismissRequest = { sections = false }) {
                for (s in LibrarySections.all) {
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        leadingIcon = { Icon(s.icon, contentDescription = null) },
                        trailingIcon = { if (s.kind == LibraryState.kind) Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = { LibraryState.kind = s.kind; sections = false },
                    )
                }
            }
        }
        Box {
            BarChip(LibraryRules.sortLabel(LibraryState.order), Icons.AutoMirrored.Rounded.Sort, dropdown = true) { sorts = true }
            DropdownMenu(expanded = sorts, onDismissRequest = { sorts = false }) {
                for (s in LibraryRules.sorts) {
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        trailingIcon = { if (s.order == LibraryState.order) Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = { LibraryState.order = s.order; sorts = false },
                    )
                }
            }
        }
        val fav = LibraryState.favoritesOnly
        BarChip("Favourites", if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, selected = fav) {
            LibraryState.favoritesOnly = !fav
        }
    }
}

@Composable
private fun LibraryList(q: LibraryQuery, onOpen: (MediaItem) -> Unit, onMenu: (MediaItem) -> Unit, modifier: Modifier) {
    LaunchedEffect(q) { LibraryState.refreshIfStale(q) }
    val list = LibraryState.list(q)
    val grid = LibraryState.grid(q)
    val lastVisible by remember(grid) { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 } }
    LaunchedEffect(q, lastVisible, list.items.size, list.loading, list.error, list.hasMore) {
        if (LibraryRules.shouldLoadMore(list, lastVisible)) LibraryState.loadMore(q)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = itemLayout(maxWidth, maxHeight)
        if (list.empty) {
            CentredNote(if (q.favoritesOnly) "No favourite ${LibrarySections.of(q.kind).label.lowercase()}" else "No ${LibrarySections.of(q.kind).label.lowercase()}")
            return@BoxWithConstraints
        }
        LazyVerticalGrid(
            columns = layout.columns,
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.cards) 4.dp else 2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            mediaItems("l", list.items, layout, onOpen, onMenu)
            if (list.loading || list.error != null) {
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                    ListNote(list.error, loading = list.loading, onRetry = list.error?.let { { LibraryState.retry(q) } })
                }
            }
        }
    }
}

/** For tests and the screenshots: the section, sort and filter back to their defaults, no lists. */
internal fun LibraryState.resetAll() {
    kind = MediaKind.PLAYLIST
    order = LibraryOrder.RECENT
    favoritesOnly = false
    stack.clear()
    for (k in LibrarySections.all) for (o in LibraryRules.sorts) for (f in listOf(false, true)) reset(LibraryQuery(k.kind, o.order, f))
}
