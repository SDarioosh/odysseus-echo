package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page's "Now playing" tab: the selected room's player, queue summary
// and room chip (docs/odysseus/music.md, "Now playing"). Big artwork on the left, the track,
// progress, transport, shuffle/repeat, volume and the queue on the right (one column on a narrow
// or portrait screen). Only the controls the player takes are shown. This device's room falls back
// to Sendspin's own state while Music Assistant's has no track. Nothing here scrolls or drags
// horizontally. The rules are in NowPlayingRules (pure, tested).

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.QueueSummary
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.player.Artwork
import com.msp1974.vacompanion.odysseus.player.OnControl
import com.msp1974.vacompanion.odysseus.player.Progress
import com.msp1974.vacompanion.odysseus.player.RoomChip
import com.msp1974.vacompanion.odysseus.player.SkipButton
import com.msp1974.vacompanion.odysseus.player.VolumeBar
import com.msp1974.vacompanion.odysseus.player.commandFor
import com.msp1974.vacompanion.odysseus.player.formatPlayerTime
import com.msp1974.vacompanion.odysseus.player.toPlayerState
import kotlinx.coroutines.delay

private val PAD = 20.dp
private val CHIP_ROW = 44.dp

@Composable
fun NowPlayingTab(modifier: Modifier = Modifier) {
    val context = LocalHomePageContext.current
    val roomId = MusicRepository.selectedRoomId
    val rooms = MusicRepository.rooms
    val self = MusicRepository.selfRoom
    val sendspin = context.nowPlaying

    val ma = rememberPlayerState(roomId, sendspin)
    val sendspinState = if (sendspin.active) sendspin.toPlayerState() else null
    val view = NowPlayingRules.view(roomId, ma, self?.entityId, sendspinState)

    // What the tab draws, its artwork and where its controls go.
    val state: PlayerState? = when (view) {
        is NowPlayingView.Ma -> view.state
        is NowPlayingView.Sendspin -> view.state
        is NowPlayingView.Nothing -> view.state
    }
    val maArt = rememberRoomArt(if (view is NowPlayingView.Sendspin) null else state)
    // Sendspin's artUrl is the server's LAN address: only its pushed artwork is used.
    val art: Bitmap? = if (view is NowPlayingView.Sendspin) view.state.artwork else maArt
    val onControl: OnControl = if (view is NowPlayingView.Sendspin) {
        { control, value ->
            // Sendspin cycles repeat itself through the modes the server offers.
            val v = if (control == Control.REPEAT) null else value
            sendspin.commandFor(control, v)?.let(context.onSendspinCommand)
        }
    } else {
        { control, value -> roomId?.let { MusicActions.control(it, control, value) } }
    }

    // The queue summary, asked for again when the room or the track changes.
    var queue by remember(roomId) { mutableStateOf<QueueSummary?>(null) }
    val queueKey = NowPlayingRules.queueKey(roomId, state)
    LaunchedEffect(queueKey) {
        queue = if (roomId == null || view is NowPlayingView.Nothing) null else MusicActions.queue(roomId)
    }

    BoxWithConstraints(modifier.padding(PAD)) {
        val w = maxWidth
        val h = maxHeight
        val landscape = w > h * 1.2f
        Column(Modifier.fillMaxSize()) {
            NowPlayingRoomRow(roomId, playing = state?.playing == true && view !is NowPlayingView.Nothing)
            Spacer(Modifier.height(12.dp))
            val bodyHeight = h - CHIP_ROW - 12.dp
            if (landscape) {
                val artSize = min(bodyHeight, w * 0.45f)
                // Centred in the height left: on a tablet the art is limited by the width.
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(art, artSize, RoundedCornerShape(artSize * 0.06f))
                    Spacer(Modifier.width(PAD + 4.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Details(view, state, roomId, queue, onControl, large = w >= 900.dp)
                    }
                }
            } else {
                val artSize = min(w * 0.6f, bodyHeight * 0.42f)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(art, artSize, RoundedCornerShape(artSize * 0.06f))
                    Spacer(Modifier.height(PAD))
                    Details(view, state, roomId, queue, onControl, large = w >= 600.dp)
                }
            }
        }
    }
}

/** The right-hand column: the track and its controls, or "Nothing playing". */
@Composable
private fun Details(
    view: NowPlayingView,
    state: PlayerState?,
    roomId: String?,
    queue: QueueSummary?,
    onControl: OnControl,
    large: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val s = if (large) 1.3f else 1f
    if (view is NowPlayingView.Nothing) {
        NothingPlaying(roomId, state, onControl, s)
        return
    }
    val st = state ?: return
    Text(
        st.title?.takeIf { it.isNotBlank() } ?: "Unknown track", fontSize = (26 * s).sp, fontWeight = FontWeight.SemiBold,
        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    val sub = listOfNotNull(st.artist?.takeIf { it.isNotBlank() }, st.album?.takeIf { it.isNotBlank() }).joinToString(" · ")
    if (sub.isNotEmpty()) {
        Text(sub, fontSize = (17 * s).sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val clock = MusicActions.clockFor(st)
    if ((st.durationS ?: 0.0) > 0.0) {
        Progress(st, clock)
        NowPlayingTimes(st, clock, s)
    }
    Spacer(Modifier.height(6.dp))
    Controls(st, onControl, s)
    VolumeBar(st, onControl, Modifier.fillMaxWidth(), iconSize = (24 * s).dp)
    if (NowPlayingRules.showQueue(queue)) {
        Spacer(Modifier.height(4.dp))
        NowPlayingRules.nextLine(queue)?.let {
            Text(it, fontSize = (15 * s).sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        NowPlayingRules.countLine(queue)?.let {
            Text(it, fontSize = (14 * s).sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Elapsed and total time under the progress bar, redrawn every second while it plays. */
@Composable
private fun NowPlayingTimes(state: PlayerState, clock: () -> Long, s: Float) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(state.playing, state.positionAtMs) {
        while (state.playing) {
            now = clock()
            delay(1_000)
        }
        now = clock()
    }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(formatPlayerTime(state.positionNow(now) ?: 0.0), fontSize = (13 * s).sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(formatPlayerTime(state.durationS ?: 0.0), fontSize = (13 * s).sp, color = colors.onSurfaceVariant)
    }
}

/** Shuffle, previous, play/pause, next, repeat: each only when the player takes it. */
@Composable
private fun Controls(state: PlayerState, onControl: OnControl, s: Float) {
    val colors = MaterialTheme.colorScheme
    val icon = (30 * s).dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (Control.SHUFFLE in state.can) {
            ToggleIcon(
                if (state.shuffle) "Shuffle off" else "Shuffle on", state.shuffle, (24 * s).dp,
            ) { onControl(Control.SHUFFLE, !state.shuffle) }
        }
        SkipButton(Icons.Rounded.SkipPrevious, "Previous track", Control.PREVIOUS in state.can, icon, colors.onBackground) {
            onControl(Control.PREVIOUS, null)
        }
        val control = if (state.playing) Control.PAUSE else Control.PLAY
        if (control in state.can) {
            val size: Dp = (56 * s).dp
            Box(
                Modifier.size(size).clip(CircleShape).background(colors.onBackground, CircleShape)
                    .clickable { onControl(control, null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play", tint = colors.background,
                    modifier = Modifier.size(size * 0.58f),
                )
            }
        }
        SkipButton(Icons.Rounded.SkipNext, "Next track", Control.NEXT in state.can, icon, colors.onBackground) {
            onControl(Control.NEXT, null)
        }
        if (Control.REPEAT in state.can) {
            IconButton(onClick = { onControl(Control.REPEAT, NowPlayingRules.nextRepeat(state.repeat)) }) {
                val on = state.repeat != Repeat.OFF
                Icon(
                    if (state.repeat == Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = when (state.repeat) {
                        Repeat.OFF -> "Repeat off"
                        Repeat.ALL -> "Repeat all"
                        Repeat.ONE -> "Repeat one"
                    },
                    tint = if (on) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size((24 * s).dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleIcon(description: String, on: Boolean, size: Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick) {
        Icon(
            Icons.Rounded.Shuffle, contentDescription = description,
            tint = if (on) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(size),
        )
    }
}

/** "Nothing playing in <room>", its volume, and "Move here" while another room plays. */
@Composable
private fun NothingPlaying(roomId: String?, idle: PlayerState?, onControl: OnControl, s: Float) {
    val colors = MaterialTheme.colorScheme
    val rooms = MusicRepository.rooms
    Text(
        "Nothing playing in ${NowPlayingRules.roomName(roomId, rooms)}", fontSize = (22 * s).sp,
        fontWeight = FontWeight.Medium, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (idle != null) {
        Spacer(Modifier.height(8.dp))
        VolumeBar(idle, onControl, Modifier.fillMaxWidth(), iconSize = (24 * s).dp)
    }
    val others = rooms.filter { it.entityId != roomId && it.available }.map { it.entityId }
    val playing = rememberPlayingRooms(others)
    val from = NowPlayingRules.moveFrom(roomId, rooms, playing)
    if (from != null && roomId != null) {
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = { MusicActions.transfer(from, roomId) }) {
            Text("Move here from ${NowPlayingRules.roomName(from, rooms)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The ids among [ids] whose player plays now (each followed while composed). */
@Composable
private fun rememberPlayingRooms(ids: List<String>): Set<String> {
    val playing = remember { mutableStateMapOf<String, Boolean>() }
    for (id in ids) {
        key(id) {
            val st = rememberPlayerState(id)
            val now = st?.playing == true && NowPlayingRules.hasTrack(st)
            LaunchedEffect(now) { playing[id] = now }
        }
    }
    return ids.filter { playing[it] == true }.toSet()
}

/** The room chip ("Kitchen · This Echo ▾"); a tap opens a vertical list of the rooms. */
@Composable
private fun NowPlayingRoomRow(roomId: String?, playing: Boolean) {
    val rooms = MusicRepository.rooms
    val self = MusicRepository.selfRoom
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(CHIP_ROW), verticalAlignment = Alignment.CenterVertically) {
        val room = rooms.firstOrNull { it.entityId == roomId }
        val label = room?.let { NowPlayingRules.roomLabel(it, self) } ?: "Choose a room"
        Box {
            RoomChip(
                "$label  ▾", selected = true, playing = playing,
                onClick = if (rooms.size > 1 || room == null) ({ open = true }) else null,
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (r in NowPlayingRules.pickerRooms(rooms, self)) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                NowPlayingRules.roomLabel(r, self), fontSize = 17.sp,
                                fontWeight = if (r.entityId == roomId) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        enabled = r.available,
                        onClick = {
                            open = false
                            MusicActions.select(r.entityId)
                        },
                    )
                }
            }
        }
    }
}
