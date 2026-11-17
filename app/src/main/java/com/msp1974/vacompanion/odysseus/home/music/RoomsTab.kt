package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page's "Rooms" tab: every Music Assistant room with what it plays,
// volume, move here and grouping (docs/odysseus/music.md, "Rooms"). One column on the Echo, a grid
// of cards from 840 dp (beside a tablet's Now playing pane: by card width, MusicLayout); scrolls
// vertically only (the home screen owns horizontal drags). The rules
// are in RoomsRules (pure, tested); every change goes through MusicActions.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.player.Artwork
import com.msp1974.vacompanion.odysseus.player.OnControl
import com.msp1974.vacompanion.odysseus.player.VolumeBar

/**
 * Card width from which a room is one row (art, text, play, volume, menu); else two rows. Play,
 * volume and menu take ~400 dp, so narrower one-row cards leave the room's text ~150 dp.
 */
private val ONE_ROW_FROM = 680.dp

/** A dialog the tab shows: choose the room to move music from, or the rooms to group with. */
private sealed interface RoomsDialog {
    data class MoveFrom(val target: Room, val sources: List<Room>) : RoomsDialog
    data class GroupWith(val leader: Room, val candidates: List<Room>) : RoomsDialog
}

@Composable
fun RoomsTab(modifier: Modifier = Modifier) {
    val rooms = MusicRepository.rooms
    val sendspin = LocalHomePageContext.current.nowPlaying
    // One live collection per room, here, so the move and group rules see every room's state.
    val states: Map<String, PlayerState?> = rooms.associate { room ->
        room.entityId to key(room.entityId) { rememberPlayerState(room.entityId, sendspin) }
    }
    var dialog by remember { mutableStateOf<RoomsDialog?>(null) }
    val beside = LocalMusicSidePane.current

    BoxWithConstraints(modifier) {
        // Beside the tablet's Now playing pane the list is narrower: columns by card width there.
        val columns = if (beside) MusicLayout.roomColumnsBesidePane(maxWidth.value) else RoomsRules.columns(maxWidth.value)
        val cardWidth = (maxWidth - 32.dp - 12.dp * (columns - 1)) / columns
        if (rooms.isEmpty()) {
            Text(
                "No rooms", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rooms, key = { it.entityId }) { room ->
                RoomCard(
                    room = room,
                    state = states[room.entityId],
                    oneRow = cardWidth >= ONE_ROW_FROM,
                    onMoveHere = {
                        val sources = RoomsRules.transferSources(room, rooms, states)
                        if (sources.size == 1) {
                            MusicActions.transfer(sources[0].entityId, room.entityId)
                        } else if (sources.size > 1) {
                            dialog = RoomsDialog.MoveFrom(room, sources)
                        }
                    },
                    onGroupWith = { dialog = RoomsDialog.GroupWith(room, RoomsRules.groupCandidates(room, rooms, states)) },
                    canMoveHere = RoomsRules.transferSources(room, rooms, states).isNotEmpty(),
                    canGroup = RoomsRules.groupCandidates(room, rooms, states).isNotEmpty(),
                )
            }
        }
    }

    when (val d = dialog) {
        is RoomsDialog.MoveFrom -> MoveFromDialog(d) { dialog = null }
        is RoomsDialog.GroupWith -> GroupWithDialog(d) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun RoomCard(
    room: Room,
    state: PlayerState?,
    oneRow: Boolean,
    canMoveHere: Boolean,
    canGroup: Boolean,
    onMoveHere: () -> Unit,
    onGroupWith: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selected = room.entityId == MusicRepository.selectedRoomId
    val beside = LocalMusicSidePane.current
    val shape = RoundedCornerShape(16.dp)
    val art = rememberRoomArt(state)
    val onControl: OnControl = { control, value -> MusicActions.control(room.entityId, control, value) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.onBackground.copy(alpha = 0.06f), shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.onBackground.copy(alpha = 0.12f), shape)
            .clickable {
                MusicActions.select(room.entityId)
                // Beside the Now playing pane the pane shows it; else open the Playing tab.
                if (!beside) MusicTabs.selectedId = MusicTabs.NOW_PLAYING
            }
            .alpha(if (room.available) 1f else 0.5f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(art, 52.dp, RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            RoomText(room, state, selected, Modifier.weight(1f))
            if (oneRow) {
                PlayPause(state, onControl)
                if (state?.volume != null) {
                    VolumeBar(state, onControl, Modifier.width(220.dp), iconSize = 20.dp, tint = if (selected) colors.onPrimaryContainer else colors.onBackground)
                }
            }
            RoomMenu(room, canMoveHere, canGroup, onMoveHere, onGroupWith)
        }
        if (!oneRow && state != null && (state.volume != null || RoomsRules.playPauseControl(state) != null)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayPause(state, onControl)
                if (state.volume != null) {
                    VolumeBar(state, onControl, Modifier.weight(1f), iconSize = 20.dp, tint = if (selected) colors.onPrimaryContainer else colors.onBackground)
                }
            }
        }
    }
}

@Composable
private fun RoomText(room: Room, state: PlayerState?, selected: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val main = if (selected) colors.onPrimaryContainer else colors.onBackground
    val status = RoomsRules.status(room, state)
    val badge = RoomsRules.badge(room, MusicRepository.selfRoom)
    val group = RoomsRules.groupLabel(room)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                room.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = main, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            for (tag in listOfNotNull(badge, group)) {
                Spacer(Modifier.width(8.dp))
                Text(
                    tag, fontSize = 12.sp, color = colors.onSecondaryContainer, maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        RoomsRules.areaLine(room)?.let {
            Text(it, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status.text.isNotEmpty()) {
            Text(
                status.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (status.active) main else colors.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PlayPause(state: PlayerState?, onControl: OnControl) {
    val control = RoomsRules.playPauseControl(state) ?: return
    val playing = state?.playing == true
    IconButton(onClick = { onControl(control, null) }) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun RoomMenu(room: Room, canMoveHere: Boolean, canGroup: Boolean, onMoveHere: () -> Unit, onGroupWith: () -> Unit) {
    val inGroup = RoomsRules.groupedWith(room).isNotEmpty() && !room.isGroup
    if (!canMoveHere && !canGroup && !inGroup) return
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Rounded.MoreVert, contentDescription = "Room actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (canMoveHere) {
            DropdownMenuItem(text = { Text("Move music here") }, onClick = { open = false; onMoveHere() })
        }
        if (canGroup) {
            DropdownMenuItem(text = { Text("Group with…") }, onClick = { open = false; onGroupWith() })
        }
        if (inGroup) {
            DropdownMenuItem(
                text = { Text(if (RoomsRules.isLeader(room)) "Ungroup" else "Leave group") },
                onClick = { open = false; MusicActions.ungroup(room.entityId) },
            )
        }
    }
}

@Composable
private fun MoveFromDialog(d: RoomsDialog.MoveFrom, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Move music to ${d.target.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (source in d.sources) {
                    Text(
                        "From ${source.name}", fontSize = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { MusicActions.transfer(source.entityId, d.target.entityId); onDone() }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun GroupWithDialog(d: RoomsDialog.GroupWith, onDone: () -> Unit) {
    val current = RoomsRules.groupedWith(d.leader)
    val chosen = remember { mutableStateMapOf<String, Boolean>().apply { current.forEach { put(it, true) } } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Group ${d.leader.name} with…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (room in d.candidates) {
                    val on = chosen[room.entityId] == true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { chosen[room.entityId] = !on },
                    ) {
                        Checkbox(checked = on, onCheckedChange = { chosen[room.entityId] = it })
                        Text(room.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (join, leave) = RoomsRules.groupChanges(
                    d.leader.entityId, current, chosen.filterValues { it }.keys,
                )
                if (join.isNotEmpty()) MusicActions.group(d.leader.entityId, join)
                leave.forEach { MusicActions.ungroup(it) }
                onDone()
            }) { Text("Group") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
