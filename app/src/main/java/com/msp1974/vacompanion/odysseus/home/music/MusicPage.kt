package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page (docs/odysseus/music.md), right of the Today page (of the clock
// when Today is off). A rail of tabs on the left (MusicTabs.all), the selected tab's content on
// the right, and one line at the bottom for a failed action (MusicActions.message) or "Home
// Assistant offline" (the last state is then shown greyed). Offered only while the Music page is
// on and Music Assistant has rooms (MusicRepository.available). Sized for the Echo Show 5 (960×480
// px, 960×480 dp at mdpi) with up to four tabs on the rail, and for tablets (1280×800 and up): from
// 1100×600 dp a compact Now playing for the selected room stays on the right beside every tab but
// Playing (MusicLayout). The page doesn't scroll or drag horizontally: HomeHost owns horizontal swipes.
// HomeHost also composes the page beside the one shown (e.g. while the clock shows): it then draws
// only the rail on an empty surface and follows no player (MusicPageLive), so nothing watches Home
// Assistant off-screen. The tabs come in as soon as a swipe or goTo moves the strip towards it.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.home.HomeDestination
import com.msp1974.vacompanion.odysseus.home.HomePage
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.HomePages
import com.msp1974.vacompanion.odysseus.home.HomeScreenController
import com.msp1974.vacompanion.odysseus.home.LocalHomeStripPosition
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.MusicSettings
import kotlin.math.abs

/**
 * The home screen's [HomePageContext] inside the Music page, for its tabs (their content takes
 * only a Modifier): e.g. `LocalHomePageContext.current.nowPlaying` is Sendspin's now playing.
 */
val LocalHomePageContext = compositionLocalOf { HomePageContext() }

/**
 * True while the tab is drawn beside the Now playing pane (MusicLayout.sidePane): e.g. a tap on a
 * room only selects it (the pane shows it) instead of switching to the Playing tab.
 */
val LocalMusicSidePane = compositionLocalOf { false }

/**
 * When the Music page draws its tabs (and so follows Home Assistant): while it is where the home
 * screen is going, or at least [MIN_VISIBLE] of it is on screen. Composed beside the page shown
 * but out of sight, it draws only a placeholder. Pure (MusicPageLiveTest).
 */
object MusicPageLive {
    /** The share of the page that must be on screen: a few pixels of a swipe bring it in. */
    const val MIN_VISIBLE = 0.02f

    /**
     * [isDestination]: the home screen is on (or going to) the page. [stripPosition]: HomeHost's
     * strip (LocalHomeStripPosition), null outside HomeHost (always live). [pageIndex]: the page's
     * place in HomePages.all, -1 when it isn't there (live).
     */
    fun isLive(isDestination: Boolean, stripPosition: Float?, pageIndex: Int): Boolean =
        isDestination || stripPosition == null || pageIndex < 0 ||
            1f - abs(stripPosition - pageIndex) >= MIN_VISIBLE
}

private val RAIL_WIDTH = 88.dp
private val RAIL_ITEM_HEIGHT = 76.dp
private val RAIL_ICON = 30.dp

/** The message line's height and its gap to the bottom, kept free below the content while offline. */
private val OFFLINE_LINE_SPACE = 52.dp

object MusicPage : HomePage {
    override val id: String = "music"

    /** In the page list: turned on (default) and Music Assistant has rooms. Compose state. */
    val available: Boolean get() = MusicSettings.enabled && MusicRepository.available

    /** Something plays on this device's own player (set while the page is composed). */
    private var selfPlaying by mutableStateOf(false)

    /** Contract C7: stays while something plays here, when "Stay while playing here" is on. */
    override fun idleExempt(): Boolean = MusicSettings.stayWhilePlaying && selfPlaying

    @Composable
    override fun Content(context: HomePageContext, modifier: Modifier) {
        // Only beside the page shown, out of sight: the rail on an empty surface, no player followed.
        val index = HomePages.all.indexOf(this)
        val position = LocalHomeStripPosition.current
        val live by remember(position, index) {
            derivedStateOf {
                MusicPageLive.isLive(HomeScreenController.destination == HomeDestination.Page(index), position?.invoke(), index)
            }
        }
        if (!live) {
            Row(modifier.fillMaxSize()) { Rail(MusicTabs.selected.id) }
            return
        }
        LiveContent(context, modifier)
    }

    @Composable
    private fun LiveContent(context: HomePageContext, modifier: Modifier) {
        // "Plays here": Sendspin plays, or this device's MA player says it plays.
        val self = rememberPlayerState(MusicRepository.selfRoom?.entityId)
        val playing = (context.nowPlaying.active && context.nowPlaying.playing) || self?.playing == true
        LaunchedEffect(playing) { selfPlaying = playing }
        DisposableEffect(Unit) { onDispose { selfPlaying = false } }

        val offline = !MusicRepository.reachable
        val selected = MusicTabs.selected
        CompositionLocalProvider(LocalHomePageContext provides context) {
            BoxWithConstraints(modifier.fillMaxSize()) {
                // Tablets: the tab on the left, Now playing for the selected room on the right (MusicLayout).
                val pane = MusicLayout.sidePane(maxWidth.value, maxHeight.value, selected.id)
                val paneWidth = MusicLayout.paneWidthDp(maxWidth.value).dp
                Row(Modifier.fillMaxSize()) {
                    Rail(selected.id)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        // While offline the line stays: keep the tab's content clear of it.
                        val bottom = if (offline) OFFLINE_LINE_SPACE else 0.dp
                        val dim = Modifier.fillMaxSize().padding(bottom = bottom).alpha(if (offline) 0.45f else 1f)
                        Row(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalMusicSidePane provides pane) {
                                selected.content(if (pane) Modifier.weight(1f).then(dim) else dim)
                            }
                            if (pane) {
                                Box(
                                    Modifier
                                        .width(paneWidth)
                                        .fillMaxHeight()
                                        .padding(bottom = bottom)
                                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f))
                                        .alpha(if (offline) 0.45f else 1f),
                                ) {
                                    NowPlayingTab(Modifier.fillMaxSize(), compact = true)
                                }
                            }
                        }
                        val line = MusicActions.message ?: if (offline) MUSIC_HA_OFFLINE else null
                        MessageLine(line, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
                    }
                }
            }
        }
    }

    /** A failed action or "Home Assistant offline", as a small dark pill. */
    @Composable
    private fun MessageLine(line: String?, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        // The last line stays drawn while it fades out.
        val last = remember { mutableStateOf("") }
        if (line != null) last.value = line
        AnimatedVisibility(visible = line != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
            Text(
                last.value, fontSize = 16.sp, color = colors.inverseOnSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.inverseSurface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    @Composable
    private fun Rail(selectedId: String) {
        val colors = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(vertical = 10.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (tab in MusicTabs.all) {
                val on = tab.id == selectedId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(RAIL_ITEM_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) colors.primaryContainer else colors.surface)
                        .clickable(role = Role.Tab) { MusicTabs.selectedId = tab.id },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val tint = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(RAIL_ICON))
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, fontSize = 13.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
