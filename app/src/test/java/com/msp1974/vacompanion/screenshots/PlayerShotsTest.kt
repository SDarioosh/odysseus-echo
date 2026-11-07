package com.msp1974.vacompanion.screenshots

// Odysseus fork: the shared player looks (odysseus/player/) playing and paused, the volume bar and
// the room chips, on the Echo Show 5 and a tablet, dark and light (docs/odysseus/screenshots.md).
// Fictional data only.

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.player.BarPlayer
import com.msp1974.vacompanion.odysseus.player.FullPlayer
import com.msp1974.vacompanion.odysseus.player.LinePlayer
import com.msp1974.vacompanion.odysseus.player.MiniPlayer
import com.msp1974.vacompanion.odysseus.player.OnControl
import com.msp1974.vacompanion.odysseus.player.PillPlayer
import com.msp1974.vacompanion.odysseus.player.RoomChip
import com.msp1974.vacompanion.odysseus.player.RowPlayer
import com.msp1974.vacompanion.odysseus.player.VolumeBar
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class PlayerShotsTest {
    private val art = gradientArt()
    private val noControl: OnControl = { _, _ -> }
    private val fixedClock: () -> Long = { 0L }

    private fun state(playing: Boolean, can: Set<Control> = Control.entries.toSet(), volume: Float? = 0.6f, muted: Boolean = false) = PlayerState(
        title = "Harbour Lights", artist = "The Fictional Band", album = "Songs for Nobody",
        artUrl = null, playing = playing, durationS = 214.0, positionS = 83.0, positionAtMs = 0L,
        shuffle = false, repeat = Repeat.OFF, volume = volume, muted = muted, can = can,
    )

    /** One look, playing (with artwork) and paused (no artwork: the music-note fallback). */
    private fun look(name: String, place: @Composable (content: @Composable () -> Unit) -> Unit = { Centered(it) }, player: @Composable (PlayerState, android.graphics.Bitmap?) -> Unit) {
        shots("player/$name-playing") { place { player(state(playing = true), art) } }
        shots("player/$name-paused") { place { player(state(playing = false), null) } }
    }

    @Test fun row() = look("row") { s, a -> RowPlayer(s, a, noControl) }
    @Test fun pill() = look("pill") { s, a -> PillPlayer(s, a, noControl) }
    @Test fun mini() = look("mini") { s, a -> MiniPlayer(s, a, noControl) }
    @Test fun line() = look("line") { s, a -> LinePlayer(s, noControl) }
    @Test fun bar() = look("bar", place = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { it() } }) { s, _ -> BarPlayer(s, noControl) }
    @Test fun full() = look("full", place = { Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { it() } }) { s, a ->
        FullPlayer(s, a, noControl, clock = fixedClock)
    }

    @Test fun volumeBar() = shots("player/volume") {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Labelled("60 %") { VolumeBar(state(true), noControl, Modifier.width(420.dp)) }
            Labelled("Muted") { VolumeBar(state(true, muted = true), noControl, Modifier.width(420.dp)) }
            Labelled("0 %") { VolumeBar(state(true, volume = 0f), noControl, Modifier.width(420.dp)) }
            Labelled("100 %") { VolumeBar(state(true, volume = 1f), noControl, Modifier.width(420.dp)) }
            Labelled("No volume control") { VolumeBar(state(true, can = setOf(Control.PLAY, Control.PAUSE)), noControl, Modifier.width(420.dp)) }
        }
    }

    @Test fun roomChips() = shots("player/rooms") {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoomChip("Kitchen", selected = true, playing = true, onClick = {})
                RoomChip("Living room", playing = true, onClick = {})
                RoomChip("Study", onClick = {})
                RoomChip("Bedroom", selected = false, available = false, onClick = {})
                RoomChip("Garden", selected = true, onClick = {})
                RoomChip("A very long room name that should be cut off somewhere", onClick = {})
            }
            Text(
                "Kitchen: selected + playing · Living room: playing · Study: plain · Bedroom: unavailable · Garden: selected",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
