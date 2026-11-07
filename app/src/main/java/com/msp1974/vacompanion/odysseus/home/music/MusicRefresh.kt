package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: keeps the Music page's data live while the home screen is composed (HomeHost
// calls this; it leaves composition when the screen blanks). Installs MusicRepository over the
// app's one Home Assistant client once; attaches it (live rooms) while the Music page is in view
// and detaches it when it isn't; while it isn't, probes Home Assistant (at once, every 10 min, when
// Home Assistant or the network comes back) so the page is offered once Music Assistant has rooms.
// When the rooms it had are gone (Home Assistant restarted and Music Assistant is still loading),
// it asks again soon: after 30 s, 60 s, 120 s… up to 10 min (MusicProbeSchedule).

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/** When MusicRefresh asks Home Assistant for the rooms again. Pure (MusicProbeScheduleTest). */
object MusicProbeSchedule {
    /** The first quick retry after the rooms went away. */
    const val FIRST_RETRY_MS = 30_000L

    /** Music Assistant had rooms earlier in this process. */
    @Volatile var hadRooms = false

    /** True when rooms seen earlier are gone: ask again soon instead of in 10 min. */
    fun roomsLost(hadRooms: Boolean, hasRooms: Boolean): Boolean = hadRooms && !hasRooms

    /**
     * The wait before the next probe: [MusicRepository.PROBE_MS] normally; while [roomsLost], 30 s
     * doubled for each quick retry already made ([retry], from 0), up to PROBE_MS.
     */
    fun delayMs(retry: Int, roomsLost: Boolean): Long {
        if (!roomsLost) return MusicRepository.PROBE_MS
        return (FIRST_RETRY_MS shl retry.coerceIn(0, 10)).coerceAtMost(MusicRepository.PROBE_MS)
    }
}

@Composable
fun MusicRefresh(deviceManager: DeviceManager, isNetworkConnected: Boolean, isServerConnected: Boolean) {
    val context = LocalContext.current
    ensureMusicInstalled(context, deviceManager)

    // Remember that Music Assistant had rooms (MusicProbeSchedule: retry soon when they're gone).
    val hasRooms by remember { derivedStateOf { MusicRepository.rooms.isNotEmpty() } }
    if (hasRooms) MusicProbeSchedule.hadRooms = true

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
        var retry = 0
        while (true) {
            val hasRooms = MusicRepository.rooms.isNotEmpty()
            if (hasRooms) MusicProbeSchedule.hadRooms = true
            val lost = MusicProbeSchedule.roomsLost(MusicProbeSchedule.hadRooms, hasRooms)
            val wait = MusicProbeSchedule.delayMs(retry, lost)
            retry = if (lost) retry + 1 else 0
            delay(wait)
            MusicRepository.probe(minAgeMs = if (lost) 0L else MusicRepository.PROBE_MS)
        }
    }
}
