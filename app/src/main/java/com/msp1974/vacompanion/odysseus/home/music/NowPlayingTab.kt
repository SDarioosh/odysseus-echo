package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: the Music page's "Now playing" tab: the selected room's player, queue summary
// and room chip (docs/odysseus/music.md).

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.msp1974.vacompanion.odysseus.music.MusicRepository

@Composable
fun NowPlayingTab(modifier: Modifier = Modifier) {
    // E13b fills this in
    val id = MusicRepository.selectedRoomId
    val name = MusicRepository.rooms.firstOrNull { it.entityId == id }?.name ?: "No room"
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(name, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}
