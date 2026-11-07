package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page (docs/odysseus/music.md), right of the Today page (of the clock
// when Today is off). A rail of tabs on the left (MusicTabs.all), the selected tab's content on
// the right, and one line at the bottom for a failed action (MusicActions.message) or "Home
// Assistant offline" (the last state is then shown greyed). Offered only while the Music page is
// on and Music Assistant has rooms (MusicRepository.available). Sized for the Echo Show 5 (960×480
// px, about 790×395 dp) with up to four tabs on the rail, and for tablets (1280×800 and up). The
// page doesn't scroll or drag horizontally: HomeHost owns horizontal swipes.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.msp1974.vacompanion.odysseus.home.HomePage
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.MusicSettings

/**
 * The home screen's [HomePageContext] inside the Music page, for its tabs (their content takes
 * only a Modifier): e.g. `LocalHomePageContext.current.nowPlaying` is Sendspin's now playing.
 */
val LocalHomePageContext = compositionLocalOf { HomePageContext() }

private val RAIL_WIDTH = 88.dp
private val RAIL_ITEM_HEIGHT = 76.dp
private val RAIL_ICON = 30.dp

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
        // "Plays here": Sendspin plays, or this device's MA player says it plays.
        val self = rememberPlayerState(MusicRepository.selfRoom?.entityId)
        val playing = (context.nowPlaying.active && context.nowPlaying.playing) || self?.playing == true
        LaunchedEffect(playing) { selfPlaying = playing }
        DisposableEffect(Unit) { onDispose { selfPlaying = false } }

        val offline = !MusicRepository.reachable
        val selected = MusicTabs.selected
        CompositionLocalProvider(LocalHomePageContext provides context) {
            Row(modifier.fillMaxSize()) {
                Rail(selected.id)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    selected.content(Modifier.fillMaxSize().alpha(if (offline) 0.45f else 1f))
                    val line = MusicActions.message ?: if (offline) MUSIC_HA_OFFLINE else null
                    MessageLine(line, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
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
