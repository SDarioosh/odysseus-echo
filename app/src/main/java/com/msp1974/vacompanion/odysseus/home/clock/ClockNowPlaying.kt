package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: what the clock's now-playing element and its cover-art background show, from the
// style's "Now playing from" (NowPlayingSource): this device's Sendspin player, or the Music page's
// selected room (MusicRepository, docs/odysseus/music.md). Both become one ClockMusic over the
// neutral PlayerState, so every look (row, pill, mini, line, bar, big player) and the Album
// preset's blurred background draw either. The selected room's state is followed only while the
// clock shows it and its style draws music (showsMusic: a now-playing element or the cover-art
// background; rememberPlayerState collects MusicBackend.player; MusicRefresh in HomeHost installs
// the backend), so the Music page needn't be attached. A tap opens the Music page.

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.odysseus.home.HomeDestination
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.HomePages
import com.msp1974.vacompanion.odysseus.home.HomeScreenController
import com.msp1974.vacompanion.odysseus.home.music.MusicActions
import com.msp1974.vacompanion.odysseus.home.music.MusicPage
import com.msp1974.vacompanion.odysseus.home.music.MusicTabs
import com.msp1974.vacompanion.odysseus.home.music.NowPlayingRules
import com.msp1974.vacompanion.odysseus.home.music.rememberPlayerState
import com.msp1974.vacompanion.odysseus.home.music.rememberRoomArt
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.MusicSettings
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.player.OnControl
import com.msp1974.vacompanion.odysseus.player.commandFor
import com.msp1974.vacompanion.odysseus.player.toPlayerState
import kotlinx.coroutines.delay

// A pause normally ends the Sendspin stream, so the track lingers a while to let it be resumed
// (same as ui/components/NowPlayingOverlay.kt). A paused room lingers as long, then hides too.
private const val PAUSED_LINGER_MS = 20_000L

/** The music the clock shows, from either source. */
data class ClockMusic(
    /** The track to draw; null: nothing to show. */
    val state: PlayerState?,
    /** It plays now (Sendspin: its stream is up). Not active: drawn dimmed as "Paused", then hidden. */
    val active: Boolean,
    val artwork: Bitmap?,
    /** Sendspin's dark artwork colour behind the blurred art; 0 for none. */
    val backgroundDark: Int = 0,
    /** The clock [state]'s positionAtMs is on (MusicActions.clockFor). */
    val clock: () -> Long = MusicActions.clockFor(state),
    val onControl: OnControl = { _, _ -> },
    /** A tap on the element: opens the Music page; null while the page isn't offered. */
    val onOpen: (() -> Unit)? = null,
    /** Where the music comes from ("device", or "room:" and the room): the linger never crosses a change. */
    val key: String = "",
) {
    companion object {
        val NONE = ClockMusic(state = null, active = false, artwork = null)
    }
}

/**
 * The music the clock draws now: [music] while it has a track, its last track dimmed (paused) for
 * [PAUSED_LINGER_MS] after it stops, then [ClockMusic.NONE]. [visible] is false once it's gone (the
 * element animates away; the background drops the art).
 */
data class ClockMusicShown(val visible: Boolean, val music: ClockMusic)

/**
 * True when [style] draws the music at all: a visible now-playing element, or the cover-art
 * background. Otherwise the clock follows no player (no Home Assistant subscription). Pure.
 */
fun showsMusic(style: ClockStyle): Boolean =
    style.theme.artworkBackground || style.elements.any { it.visible && it.type == ElementType.NOW_PLAYING }

/**
 * [ClockMusic] for [source]: this device's Sendspin player or the Music page's selected room;
 * [ClockMusic.NONE] without following either when [follow] is false ([showsMusic]).
 */
@Composable
fun rememberClockMusic(source: NowPlayingSource, context: HomePageContext, follow: Boolean = true): ClockMusic = when {
    !follow -> ClockMusic.NONE
    source == NowPlayingSource.ROOM -> rememberRoomMusic(context)
    else -> rememberDeviceMusic(context)
}

@Composable
private fun rememberDeviceMusic(context: HomePageContext): ClockMusic {
    val np = context.nowPlaying
    val art = rememberArtwork(np)
    val state = if (np.hasContent) np.toPlayerState() else null
    return ClockMusic(
        state = state,
        active = np.active && np.hasContent,
        artwork = art,
        backgroundDark = np.colors.backgroundDark,
        onControl = { control, value -> np.commandFor(control, value)?.let(context.onSendspinCommand) },
        onOpen = musicPageOpener(MusicRepository.selfRoom?.entityId),
        key = "device",
    )
}

@Composable
private fun rememberRoomMusic(context: HomePageContext): ClockMusic {
    // The page's selected room; before Music Assistant was asked (e.g. the Music page is off) the last one.
    val roomId = MusicRepository.selectedRoomId ?: MusicSettings.lastRoom.ifBlank { null }
    val st = rememberPlayerState(roomId, context.nowPlaying)
    val track = st?.takeIf { NowPlayingRules.hasTrack(it) }
    val art = rememberRoomArt(track)
    return ClockMusic(
        state = track,
        active = track?.playing == true,
        artwork = art,
        onControl = { control, value -> roomId?.let { MusicActions.control(it, control, value) } },
        onOpen = musicPageOpener(roomId),
        key = "room:${roomId.orEmpty()}",
    )
}

/** Opens the Music page on its Playing tab, showing [roomId] when given; null while the page isn't offered. */
@Composable
private fun musicPageOpener(roomId: String?): (() -> Unit)? {
    if (!MusicPage.available) return null
    return {
        val index = HomePages.all.indexOf(MusicPage)
        if (index >= 0) {
            if (roomId != null && roomId != MusicRepository.selectedRoomId) MusicActions.select(roomId)
            MusicTabs.selectedId = MusicTabs.NOW_PLAYING
            HomeScreenController.goTo(HomeDestination.Page(index))
        }
    }
}

/**
 * [music] with the 20 s "Paused" linger (as the original clock did). A new source or room
 * ([ClockMusic.key]) starts afresh: the previous one's track doesn't linger.
 */
@Composable
fun rememberClockMusicShown(music: ClockMusic): ClockMusicShown {
    var visible by remember(music.key) { mutableStateOf(music.active) }
    LaunchedEffect(music.key, music.active) {
        if (music.active) {
            visible = true
        } else {
            delay(PAUSED_LINGER_MS)
            visible = false
        }
    }
    // The last track, kept to draw it dimmed after it stopped and while it animates away.
    var last by remember { mutableStateOf(ClockMusic.NONE) }
    val shown = lingered(music, last)
    if (music.state != null) last = music
    return ClockMusicShown(visible && shown.state != null, shown)
}

/**
 * What to draw: [music] while it has a track; else [last]'s track, stopped (not playing, not
 * active), when it came from the same source and room ([ClockMusic.key]). Pure.
 */
fun lingered(music: ClockMusic, last: ClockMusic): ClockMusic = when {
    music.state != null -> music
    last.state != null && last.key == music.key -> last.copy(state = last.state.copy(playing = false), active = false)
    else -> ClockMusic.NONE
}
