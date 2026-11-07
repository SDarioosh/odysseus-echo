package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page's "Rooms" tab: every Music Assistant room with what it plays,
// volume, move here and grouping (docs/odysseus/music.md).

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.msp1974.vacompanion.odysseus.music.MusicRepository

@Composable
fun RoomsTab(modifier: Modifier = Modifier) {
    // E13b fills this in
    val count = MusicRepository.rooms.size
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("$count rooms", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}
