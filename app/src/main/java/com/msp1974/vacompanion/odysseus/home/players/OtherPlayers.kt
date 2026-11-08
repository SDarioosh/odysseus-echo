package com.msp1974.vacompanion.odysseus.home.players

// Odysseus fork: the one process-wide holder of "music on another speaker", as Compose state. A
// thin view over the Music page's list of media players (MusicRepository's HaMusicBackend: rooms
// and their live states over Home Assistant's websocket). Driver (composed in HomeHost) follows
// that list while the Echo's screen is in use and the setting is on, and OtherPlayersRules picks
// one speaker; the clock page and the dashboard's now-playing card show [current] whenever
// OtherPlayersRules.showOther says so, and their buttons go to that speaker through [command] (the
// backend's control). Home Assistant unreachable for a while hides it.

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.home.music.ensureMusicInstalled
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.odysseus.music.HaMusicBackend
import com.msp1974.vacompanion.odysseus.music.LivePlayer
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import timber.log.Timber

object OtherPlayers {
    /** The pick is made again this often (a paused speaker's 15 minutes run out; offline time). */
    private const val TICK_MS = 30_000L

    /** Home Assistant unreachable for longer than this hides the speaker rather than show a stale one. */
    private const val STALE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The other speaker shown when the Echo itself isn't playing; null = none. */
    var current by mutableStateOf<OtherPlayer?>(null)
        private set

    /** The media players listed now (the Music page's rooms), for the settings menu. */
    var seen by mutableStateOf<List<Room>>(emptyList())
        private set

    /** What the clock and the card show: this Echo's own music, or another speaker's. */
    fun nowPlaying(own: SendspinNowPlaying): SendspinNowPlaying {
        val other = current?.nowPlaying
        return if (OtherPlayersRules.showOther(own, other)) other!! else own
    }

    /**
     * A control on the clock or the card was pressed: to that speaker (MusicBackend.control, a
     * media_player call) when another speaker is shown, else to the Echo's own Sendspin player
     * through [sendspin]. [deviceManager] is no longer needed (the backend holds the HA client);
     * kept for the upstream card's call.
     */
    @Suppress("UNUSED_PARAMETER")
    fun command(own: SendspinNowPlaying, command: String, deviceManager: DeviceManager, sendspin: (String) -> Unit) {
        val other = current
        if (other == null || !OtherPlayersRules.showOther(own, other.nowPlaying)) {
            sendspin(command)
            return
        }
        val (control, value) = OtherPlayersRules.control(command) ?: return
        val backend = MusicRepository.backend ?: return
        scope.launch {
            try {
                backend.control(other.entityId, control, value)
                Timber.i("Other players: %s on %s", command, other.entityId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Other players: %s on %s failed: %s", command, other.entityId, e.message)
            }
        }
    }

    fun clear() {
        current = null
    }

    /**
     * Follows the backend's rooms and live states (collecting `rooms` keeps them live: the
     * websocket watches every media player) until cancelled, then clears the speaker. Runs on the
     * main thread (the Driver's effect), so state is written where Compose reads it.
     */
    private suspend fun follow(deviceManager: DeviceManager) {
        val backend = MusicRepository.backend as? HaMusicBackend ?: return
        var paused = emptyMap<String, Long>()
        var unreachableSinceMs: Long? = null
        var lastLoggedKey = ""
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(TICK_MS)
            }
        }
        try {
            combine(
                backend.rooms, backend.live, backend.reachable,
                snapshotFlow { HomeSettings.otherPlayersEntity }, ticks,
            ) { rooms, live, reachable, chosen, _ -> Input(rooms, live, reachable, chosen) }
                .collect { input ->
                    val now = System.currentTimeMillis()
                    unreachableSinceMs = if (input.reachable) null else unreachableSinceMs ?: now
                    paused = OtherPlayersRules.pausedSince(paused, input.live)
                    seen = input.rooms
                    val stale = unreachableSinceMs?.let { now - it > STALE_MS } == true
                    val speakers = OtherPlayersRules.speakers(input.rooms, input.live, paused)
                    val picked = if (stale) null
                    else OtherPlayersRules.pick(speakers, deviceManager.config.area, input.chosen, now)
                    current = picked?.let {
                        val haBase = runCatching { deviceManager.authenticationManager.getBaseUrl().toString() }.getOrDefault("")
                        OtherPlayer(it.entityId, it.room.name, OtherPlayersRules.toNowPlaying(it.live, haBase))
                    }
                    val key = picked?.let { "${it.entityId} ${it.live.status} ${it.trackKey}" } ?: ""
                    if (key != lastLoggedKey) {
                        lastLoggedKey = key
                        Timber.i("Other players: %s", picked?.let { "${it.entityId} ${it.live.status} (area '${it.room.area.orEmpty()}')" } ?: "none")
                    }
                }
        } finally {
            clear()
        }
    }

    private class Input(val rooms: List<Room>, val live: Map<String, LivePlayer>,
                        val reachable: Boolean, val chosen: String)

    /**
     * Follows the speakers while the app is in front, the setting is on, something can show it
     * ([shown]: the clock or the dashboard's now-playing card), the network is up and the Echo
     * isn't locked. Composed once, in HomeHost, which is there whether or not the clock is turned
     * on. Whatever stops it (including the screen going blank, which takes HomeHost away) clears
     * the speaker, so an old song never shows when the screen comes back.
     */
    @Composable
    fun Driver(deviceManager: DeviceManager, networkConnected: Boolean, shown: Boolean) {
        val context: Context = LocalContext.current
        val locked by LockMode.locked.collectAsState()
        val on = HomeSettings.otherPlayersEnabled && shown && networkConnected && !locked
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(on, lifecycle) {
            if (!on) {
                clear()
                return@LaunchedEffect
            }
            ensureMusicInstalled(context, deviceManager)
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { follow(deviceManager) }
        }
    }
}
