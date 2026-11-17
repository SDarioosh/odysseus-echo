package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Library tab's sections, in the order its section menu lists them
// (docs/odysseus/music.md, "Library"). Adding a media type is one line here.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.ui.graphics.vector.ImageVector
import com.msp1974.vacompanion.odysseus.music.MediaKind

/** One section: the [kind] it lists, its [label] (plural) and the [icon] its placeholders use. */
data class LibrarySection(val kind: MediaKind, val label: String, val icon: ImageVector)

object LibrarySections {
    val all: List<LibrarySection> = listOf(
        LibrarySection(MediaKind.PLAYLIST, "Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
        LibrarySection(MediaKind.ALBUM, "Albums", Icons.Rounded.Album),
        LibrarySection(MediaKind.ARTIST, "Artists", Icons.Rounded.Person),
        LibrarySection(MediaKind.RADIO, "Radio", Icons.Rounded.Radio),
        LibrarySection(MediaKind.TRACK, "Tracks", Icons.Rounded.MusicNote),
        LibrarySection(MediaKind.PODCAST, "Podcasts", Icons.Rounded.Podcasts),
        LibrarySection(MediaKind.AUDIOBOOK, "Audiobooks", Icons.Rounded.AutoStories),
    )

    /** The section of [kind] (the first one for a kind without a section). */
    fun of(kind: MediaKind): LibrarySection = all.firstOrNull { it.kind == kind } ?: all.first()

    /** The placeholder icon for an item of [kind]. */
    fun icon(kind: MediaKind): ImageVector = of(kind).icon
}
