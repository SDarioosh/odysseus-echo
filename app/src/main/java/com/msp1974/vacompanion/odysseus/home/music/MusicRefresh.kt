package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: keeps the Music page's data live while the home screen is composed (HomeHost
// calls this; it leaves composition when the screen blanks). Installs MusicRepository over the
// app's one Home Assistant client once; attaches it (live rooms) while the Music page is in view
// and detaches it when it isn't; while it isn't, probes Home Assistant (at once, every 10 min, when
// Home Assistant or the network comes back) so the page is offered once Music Assistant has rooms.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.ha.HaClient
import com.msp1974.vacompanion.odysseus.home.HomeDestination
import com.msp1974.vacompanion.odysseus.home.HomePages
import com.msp1974.vacompanion.odysseus.home.HomeScreenController
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.MusicSettings
import kotlinx.coroutines.delay

/** Installs MusicRepository over HaClient once per process. Safe to call from anywhere, often. */
fun ensureMusicInstalled(context: android.content.Context, deviceManager: DeviceManager) {
    if (MusicRepository.backend != null) return
    synchronized(MusicRepository) {
        if (MusicRepository.backend != null) return
        MusicRepository.installHa(context.applicationContext, HaClient.get(deviceManager), deviceManager.config)
    }
}

@Composable
fun MusicRefresh(deviceManager: DeviceManager, isNetworkConnected: Boolean, isServerConnected: Boolean) {
    val context = LocalContext.current
    ensureMusicInstalled(context, deviceManager)

    val showing = (HomeScreenController.destination as? HomeDestination.Page)
        ?.let { HomePages.all.getOrNull(it.index) } == MusicPage
    LaunchedEffect(showing) {
        if (showing) MusicRepository.attach() else MusicRepository.detach()
    }
    DisposableEffect(Unit) { onDispose { MusicRepository.detach() } }

    val wanted = HomeSettings.enabled && MusicSettings.enabled
    LaunchedEffect(wanted, showing, isNetworkConnected, isServerConnected) {
        if (!wanted || showing || !isNetworkConnected) return@LaunchedEffect
        // Ask again at once after a failure or with nothing to offer (e.g. Home Assistant came back).
        val fresh = MusicRepository.reachable && MusicRepository.available
        MusicRepository.probe(minAgeMs = if (fresh) 60_000L else 0L)
        while (true) {
            delay(MusicRepository.PROBE_MS)
            MusicRepository.probe()
        }
    }
}
