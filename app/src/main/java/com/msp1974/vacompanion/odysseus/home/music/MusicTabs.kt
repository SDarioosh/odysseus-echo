package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page's tabs, top to bottom on its left rail (docs/odysseus/music.md,
// "Adding a tab"). A tab is one entry in MusicTabs.all; its content is drawn right of the rail and
// reads what it needs from MusicRepository, MusicActions, the helpers in MusicLive.kt and
// LocalHomePageContext. Tabs must not scroll or drag horizontally: the home screen owns those.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** One tab: a stable [id], a short [label] (one word fits the rail), its [icon] and its [content]. */
data class MusicTab(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val content: @Composable (Modifier) -> Unit,
)

object MusicTabs {
    const val NOW_PLAYING = "now_playing"
    const val ROOMS = "rooms"
    const val LIBRARY = "library"
    const val SEARCH = "search"

    /** Top to bottom. The first one is the default. */
    val all: List<MusicTab> = listOf(
        MusicTab(NOW_PLAYING, "Playing", Icons.Rounded.PlayCircle) { NowPlayingTab(it) },
        MusicTab(ROOMS, "Rooms", Icons.Rounded.Speaker) { RoomsTab(it) },
        MusicTab(LIBRARY, "Library", Icons.Rounded.LibraryMusic) { LibraryTab(it) },
        MusicTab(SEARCH, "Search", Icons.Rounded.Search) { SearchTab(it) },
    )

    /**
     * The tab chosen on the rail, process-wide (survives the page leaving composition and the
     * screen blanking; not across restarts). Compose state.
     */
    var selectedId: String by mutableStateOf(NOW_PLAYING)

    /** The selected tab (the first one when [selectedId] isn't a tab). */
    val selected: MusicTab get() = all[indexOf(selectedId, all.map { it.id })]

    /** Index of [id] in [ids], or 0 when it isn't there. Pure. */
    fun indexOf(id: String?, ids: List<String>): Int = ids.indexOf(id).coerceAtLeast(0)
}
