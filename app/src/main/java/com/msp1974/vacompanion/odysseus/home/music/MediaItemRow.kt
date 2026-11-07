package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the pieces the Library and Search tabs share (docs/odysseus/music.md, "Library"):
// an item as a row (Echo) or a card (tablet), a section header, a chip for the tab's top bar, the
// list's footer, and the artist / album detail view. Tap an item to open it (artists and albums)
// or its play menu (everything else); the ⋮ always opens the play menu.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.music.MediaDetail
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import kotlinx.coroutines.CancellationException

/**
 * How a list of items is laid out at its size (LibraryRules.grid): a grid of [cardCols] x
 * [rowCols] columns, where a card spans [rowCols] (so [cardCols] cards to a line) and a row spans
 * [cardCols] (so [rowCols] rows to a line). Tracks are always rows; the rest are cards while [cards].
 */
data class ItemLayout(val cards: Boolean, val cardCols: Int, val rowCols: Int) {
    val columns: GridCells get() = GridCells.Fixed(cardCols * rowCols)

    /** Whether [kind] is drawn as a card. */
    fun asCard(kind: MediaKind): Boolean = cards && kind != MediaKind.TRACK

    /** The columns an item of [kind] spans. */
    fun span(kind: MediaKind): Int = if (asCard(kind)) rowCols else cardCols
}

/** The layout for a list [maxWidth] x [maxHeight] (with 12 dp padding each side). */
fun itemLayout(maxWidth: Dp, maxHeight: Dp): ItemLayout {
    val g = LibraryRules.grid(maxWidth.value - 24f, maxHeight.value)
    return ItemLayout(g.cards, g.cardCols, g.rowCols)
}

/** What a tap on [item] does: open artists and albums, the play menu for the rest. */
fun openOrMenu(item: MediaItem, open: (MediaItem) -> Unit, menu: (MediaItem) -> Unit) =
    if (LibraryRules.opensDetail(item.kind)) open(item) else menu(item)

/** An item as a row: thumbnail, name, subtitle (♥ for favourites), ⋮. */
@Composable
fun MediaItemRow(item: MediaItem, onClick: () -> Unit, onMenu: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp),
    ) {
        MediaThumb(item, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onBackground)
            val sub = item.subtitle
            if (sub.isNotBlank() || item.favorite) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.favorite) {
                        Icon(Icons.Rounded.Favorite, contentDescription = "Favourite", tint = colors.primary, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                }
            }
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Play options", tint = colors.onSurfaceVariant)
        }
    }
}

/** An item as a card: square art filling the cell, name, subtitle; ⋮ beside the text. */
@Composable
fun MediaItemCard(item: MediaItem, onClick: () -> Unit, onMenu: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        val art = maxWidth
        Column {
            val round = item.kind == MediaKind.ARTIST
            MediaThumb(item, art, shape = RoundedCornerShape(if (round) art / 2 else 8.dp))
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onBackground)
                    Text(
                        item.subtitle.ifBlank { " " }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Rounded.MoreVert, contentDescription = "Play options", tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onMenu).padding(4.dp),
                )
            }
        }
    }
}

/** A heading line across the whole grid. */
fun LazyGridScope.header(key: String, text: String) {
    item(key = "h:$key", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
        )
    }
}

/** Items as rows or cards (by [layout]) in a grid scope. */
fun LazyGridScope.mediaItems(
    keyPrefix: String,
    list: List<MediaItem>,
    layout: ItemLayout,
    onOpen: (MediaItem) -> Unit,
    onMenu: (MediaItem) -> Unit,
) {
    items(list, key = { "$keyPrefix:${it.uri}" }, span = { GridItemSpan(layout.span(it.kind)) }) { item ->
        val click = { openOrMenu(item, onOpen, onMenu) }
        if (layout.asCard(item.kind)) MediaItemCard(item, click) { onMenu(item) }
        else MediaItemRow(item, click) { onMenu(item) }
    }
}

/** A chip of the tab's top bar: [icon], [text] and, for menus, a ▾. */
@Composable
fun BarChip(text: String, icon: ImageVector? = null, selected: Boolean = false, dropdown: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val content = if (selected) colors.onPrimary else colors.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) colors.primary else colors.onBackground.copy(alpha = 0.08f), shape)
            .border(1.dp, if (selected) colors.primary else colors.onBackground.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = if (dropdown) 6.dp else 14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 15.sp, color = content, maxLines = 1)
        if (dropdown) Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = content)
    }
}

/** A centred line in a list (empty, failed with a retry, or loading). */
@Composable
fun ListNote(text: String?, loading: Boolean = false, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        if (text != null) {
            Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
    }
}

/** A detail view's data: loading, failed (a short reason) or loaded. */
sealed interface DetailLoad {
    data object Loading : DetailLoad
    data class Failed(val message: String) : DetailLoad
    data class Loaded(val detail: MediaDetail) : DetailLoad
}

/** The last details loaded (artists and albums), so going back and forth doesn't ask again. */
object MusicDetails {
    val cache = ArtCache<MediaDetail>(8)
}

/** [item]'s detail (MusicBackend.detail), cached; [attempt] changes to try again. */
@Composable
fun rememberDetail(item: MediaItem, attempt: Int): DetailLoad {
    val backend = MusicRepository.backend
    val load by produceState<DetailLoad>(
        MusicDetails.cache.get(item.uri)?.let { DetailLoad.Loaded(it) } ?: DetailLoad.Loading, item.uri, backend, attempt,
    ) {
        if (value is DetailLoad.Loaded) return@produceState
        if (backend == null) {
            value = DetailLoad.Failed(MUSIC_HA_OFFLINE)
            return@produceState
        }
        value = DetailLoad.Loading
        value = try {
            DetailLoad.Loaded(backend.detail(item).also { MusicDetails.cache.put(item.uri, it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DetailLoad.Failed(musicActionMessage("", e))
        }
    }
    return load
}

/**
 * An artist (its albums, then its tracks) or an album (its tracks), with Back, Play and the play
 * menu for the item itself. [onOpen] opens an album from an artist; [onMenu] opens a play menu.
 */
@Composable
fun MediaDetailView(item: MediaItem, onBack: () -> Unit, onOpen: (MediaItem) -> Unit, onMenu: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    var attempt by remember(item.uri) { mutableIntStateOf(0) }
    val load = rememberDetail(item, attempt)
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val layout = itemLayout(maxWidth, maxHeight)
        val gap = 8.dp
        val pad = 12.dp
        val big = layout.cards
        LazyVerticalGrid(
            columns = layout.columns,
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(start = pad, end = pad, top = 4.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(if (layout.cards) 4.dp else 2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.onBackground)
                    }
                    val art = if (big) 120.dp else 72.dp
                    MediaThumb(item, art, shape = RoundedCornerShape(if (item.kind == MediaKind.ARTIST) art / 2 else 8.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name, fontSize = if (big) 26.sp else 21.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onBackground,
                        )
                        if (item.subtitle.isNotBlank()) {
                            Text(item.subtitle, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = {
                                val choices = PlayMenu.rooms(MusicRepository.rooms, MusicRepository.selfRoom?.entityId)
                                val room = PlayMenu.defaultRoom(choices, MusicRepository.selectedRoomId)
                                if (room != null) PlayMenu.play(item, room.room, PlayOption.PLAY_NOW) else onMenu(item)
                            }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Play")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onMenu(item) }) { Text("More…") }
                        }
                    }
                }
            }
            when (load) {
                DetailLoad.Loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { ListNote(null, loading = true) }
                is DetailLoad.Failed -> item(key = "failed", span = { GridItemSpan(maxLineSpan) }) {
                    ListNote(load.message, onRetry = { attempt++ })
                }
                is DetailLoad.Loaded -> {
                    val d = load.detail
                    if (d.albums.isNotEmpty()) {
                        header("albums", "Albums")
                        mediaItems("a", d.albums, layout, onOpen, onMenu)
                    }
                    if (d.tracks.isNotEmpty()) {
                        header("tracks", if (item.kind == MediaKind.ARTIST) "Tracks" else "${d.tracks.size} tracks")
                        mediaItems("t", d.tracks, layout, onOpen, onMenu)
                    }
                    if (d.albums.isEmpty() && d.tracks.isEmpty()) {
                        item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                            ListNote(if (d.listed) "Nothing found for ${item.name}" else "Use Play or More… to play it")
                        }
                    }
                }
            }
        }
    }
}

/** A box that fills its space with [content] centred; for a tab's empty states. */
@Composable
fun CentredNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
