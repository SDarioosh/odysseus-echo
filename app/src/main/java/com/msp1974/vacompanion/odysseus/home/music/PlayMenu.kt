package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the play menu of any Library or Search item (docs/odysseus/music.md, "Play
// menu"): Play now, Play next, Add to queue, Replace queue, Radio from this (tracks and artists),
// in the selected room by default, with a way to pick another room ("here" is this device's own
// room when it was found). A dialog with one vertical list. The rules (which options, which room,
// what the backend is asked) are pure and unit-tested (PlayMenuTest); playing goes through
// MusicActions.run, so Lock mode and an unreachable Home Assistant show their usual line.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.msp1974.vacompanion.odysseus.music.Enqueue
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MediaKind
import com.msp1974.vacompanion.odysseus.music.MusicBackend
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.Room
import kotlinx.coroutines.Job

/** One line of the play menu: its [label], and what MA is asked ([enqueue], [radio]). */
enum class PlayOption(val label: String, val enqueue: Enqueue, val radio: Boolean = false) {
    PLAY_NOW("Play now", Enqueue.PLAY),
    PLAY_NEXT("Play next", Enqueue.NEXT),
    ADD("Add to queue", Enqueue.ADD),
    REPLACE("Replace queue", Enqueue.REPLACE),
    RADIO("Radio from this", Enqueue.PLAY, radio = true),
}

/** A room the menu can play in; [here] for this device's own room. */
data class PlayRoom(val room: Room, val here: Boolean) {
    val label: String get() = if (here) "${room.name} (here)" else room.name
}

object PlayMenu {
    /** The options for an item of [kind]: a station only plays; radio only from tracks and artists. */
    fun options(kind: MediaKind): List<PlayOption> = when (kind) {
        MediaKind.RADIO -> listOf(PlayOption.PLAY_NOW)
        MediaKind.TRACK, MediaKind.ARTIST -> PlayOption.entries.toList()
        else -> PlayOption.entries - PlayOption.RADIO
    }

    /**
     * The rooms to choose from: this device's room first ("here"), then the others; available
     * Music Assistant rooms only (only MA plays its library).
     */
    fun rooms(rooms: List<Room>, selfId: String?): List<PlayRoom> {
        val usable = rooms.filter { it.available && it.musicAssistant }
        val here = usable.filter { it.entityId == selfId }.map { PlayRoom(it, here = true) }
        return here + usable.filter { it.entityId != selfId }.map { PlayRoom(it, here = false) }
    }

    /** The room it plays in unless another is picked: the selected room, else here, else the first. */
    fun defaultRoom(choices: List<PlayRoom>, selectedId: String?): PlayRoom? =
        choices.firstOrNull { it.room.entityId == selectedId } ?: choices.firstOrNull { it.here } ?: choices.firstOrNull()

    /** What the backend is asked for [option] on [item] in [roomId]. */
    suspend fun perform(backend: MusicBackend, item: MediaItem, roomId: String, option: PlayOption) =
        backend.play(item, roomId, option.enqueue, option.radio)

    /**
     * After a success: true when the page should show the room playing (selected, on the Playing
     * tab), i.e. what played changed at once; false for queue additions.
     */
    fun showsPlaying(option: PlayOption): Boolean = option != PlayOption.PLAY_NEXT && option != PlayOption.ADD

    /** The short line after a queue addition (null for the others). */
    fun doneText(option: PlayOption, roomName: String): String? = when (option) {
        PlayOption.PLAY_NEXT -> "Plays next in $roomName"
        PlayOption.ADD -> "Added to the queue in $roomName"
        else -> null
    }

    /** Plays [item] in [room] through MusicActions (errors become its message line). */
    fun play(item: MediaItem, room: Room, option: PlayOption): Job? = MusicActions.run(option.label) { backend ->
        perform(backend, item, room.entityId, option)
        if (showsPlaying(option)) {
            MusicRepository.select(room.entityId)
            MusicTabs.selectedId = MusicTabs.NOW_PLAYING
        }
        doneText(option, room.name)?.let { MusicActions.show(it) }
    }
}

private fun PlayOption.icon(): ImageVector = when (this) {
    PlayOption.PLAY_NOW -> Icons.Rounded.PlayArrow
    PlayOption.PLAY_NEXT -> Icons.AutoMirrored.Rounded.PlaylistPlay
    PlayOption.ADD -> Icons.AutoMirrored.Rounded.PlaylistAdd
    PlayOption.REPLACE -> Icons.Rounded.SwapHoriz
    PlayOption.RADIO -> Icons.Rounded.Radio
}

/**
 * The play menu for [item] as a dialog; [onDismiss] closes it (also after an option ran). Rooms
 * come from MusicRepository; "Change" swaps the options for the room list.
 */
@Composable
fun PlayMenuDialog(item: MediaItem, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { PlayMenuContent(item, onDismiss) }
}

/** The play menu's card (PlayMenuDialog's content; also drawn alone by the screenshot tests). */
@Composable
fun PlayMenuContent(item: MediaItem, onDismiss: () -> Unit) {
    val choices = PlayMenu.rooms(MusicRepository.rooms, MusicRepository.selfRoom?.entityId)
    var picked by remember(item.uri) { mutableStateOf<String?>(null) }
    var choosing by remember(item.uri) { mutableStateOf(false) }
    val room = choices.firstOrNull { it.room.entityId == picked } ?: PlayMenu.defaultRoom(choices, MusicRepository.selectedRoomId)
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, modifier = Modifier.widthIn(max = 460.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                MediaThumb(item, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = item.subtitle.ifBlank { LibrarySections.of(item.kind).label.removeSuffix("s") }
                    Text(sub, fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
            ) {
                Text(
                    if (room == null) "No room to play in" else "in ${room.label}",
                    fontSize = 15.sp, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (choices.size > 1) {
                    TextButton(onClick = { choosing = !choosing }) { Text(if (choosing) "Back" else "Change") }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (choosing) {
                for (c in choices) {
                    MenuLine(
                        label = c.label,
                        icon = if (c.room.entityId == room?.room?.entityId) Icons.Rounded.Check else null,
                    ) {
                        picked = c.room.entityId
                        choosing = false
                    }
                }
            } else {
                for (option in PlayMenu.options(item.kind)) {
                    MenuLine(option.label, option.icon(), enabled = room != null) {
                        room?.let { PlayMenu.play(item, it.room, option) }
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuLine(label: String, icon: ImageVector?, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        else Spacer(Modifier.size(24.dp))
        Text(label, fontSize = 17.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

