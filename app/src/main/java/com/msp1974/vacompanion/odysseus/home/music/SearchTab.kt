package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page's "Search" tab (docs/odysseus/music.md, "Search"): a text field on
// top (the on-screen keyboard comes up under it; results stay above the keyboard and scroll),
// Music Assistant's search 400 ms after the last keystroke from 2 characters, up to 10 results per
// kind grouped Artists, Albums, Tracks, Playlists, Radio, … Artists and albums open the same
// detail view as the Library; everything has a play menu. Vertical scrolling only.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The Search tab's state, process-wide Compose state: the text, the last results and open detail views. */
object SearchState {
    var text by mutableStateOf("")

    /** The query [results] are for (null: none yet). */
    var resultsFor by mutableStateOf<String?>(null)
    var results by mutableStateOf<Map<MediaKind, List<MediaItem>>>(emptyMap())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** Detail views opened from a result; the last one shows. */
    val stack = mutableStateListOf<MediaItem>()

    /** The results' scroll position. */
    val grid = LazyGridState()

    /** Runs the search for [query] (debounced by the caller's effect); keeps the last results on failure. */
    suspend fun run(query: String?) {
        if (query == null) {
            resultsFor = null
            results = emptyMap()
            error = null
            loading = false
            return
        }
        if (query == resultsFor && error == null) return
        delay(LibraryRules.SEARCH_DEBOUNCE_MS)
        val backend = MusicRepository.backend
        if (backend == null) {
            error = MUSIC_HA_OFFLINE
            return
        }
        loading = true
        try {
            val found = backend.search(query, LibraryRules.searchKinds.toSet(), LibraryRules.SEARCH_LIMIT)
            results = found
            resultsFor = query
            error = null
            grid.scrollToItem(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = musicActionMessage("", e)
        } finally {
            loading = false
        }
    }

    /** For tests and the screenshots: empty. */
    internal fun clear() {
        text = ""
        resultsFor = null
        results = emptyMap()
        loading = false
        error = null
        stack.clear()
    }
}

@Composable
fun SearchTab(modifier: Modifier = Modifier) {
    var menuItem by remember { mutableStateOf<MediaItem?>(null) }
    val open = { item: MediaItem -> SearchState.stack.add(item); Unit }
    val menu = { item: MediaItem -> menuItem = item }

    val query = LibraryRules.searchQuery(SearchState.text)
    LaunchedEffect(query) { SearchState.run(query) }

    val detail = SearchState.stack.lastOrNull()
    if (detail != null) {
        MediaDetailView(
            detail,
            onBack = { SearchState.stack.removeAt(SearchState.stack.lastIndex) },
            onOpen = open, onMenu = menu, modifier = modifier,
        )
    } else {
        Column(modifier.imePadding()) {
            SearchField()
            SearchResults(query, open, menu, Modifier.weight(1f))
        }
    }
    menuItem?.let { PlayMenuDialog(it) { menuItem = null } }
}

@Composable
private fun SearchField() {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = SearchState.text,
        onValueChange = { SearchState.text = it.take(100) },
        singleLine = true,
        placeholder = { Text("Artists, albums, songs, radio…") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (SearchState.text.isNotEmpty()) {
                IconButton(onClick = { SearchState.text = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
            }
        },
        textStyle = TextStyle(fontSize = 18.sp),
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SearchResults(query: String?, onOpen: (MediaItem) -> Unit, onMenu: (MediaItem) -> Unit, modifier: Modifier) {
    val groups = LibraryRules.searchGroups(SearchState.results)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = itemLayout(maxWidth, maxHeight)
        when {
            query == null -> {
                CentredNote(if (SearchState.text.isBlank()) "Search Music Assistant" else "Type at least ${LibraryRules.SEARCH_MIN_CHARS} letters")
                return@BoxWithConstraints
            }
            groups.isEmpty() && SearchState.loading -> {
                ListNote(null, loading = true)
                return@BoxWithConstraints
            }
            groups.isEmpty() && SearchState.error != null -> {
                ListNote(SearchState.error)
                return@BoxWithConstraints
            }
            groups.isEmpty() && SearchState.resultsFor == query -> {
                CentredNote("Nothing found for “$query”")
                return@BoxWithConstraints
            }
        }
        LazyVerticalGrid(
            columns = layout.columns,
            state = SearchState.grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(if (layout.cards) 4.dp else 2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (SearchState.loading || SearchState.error != null) {
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    ListNote(SearchState.error, loading = SearchState.loading)
                }
            }
            for ((kind, items) in groups) {
                header(kind.name, LibrarySections.of(kind).label)
                mediaItems(kind.name, items, layout, onOpen, onMenu)
            }
        }
    }
}
