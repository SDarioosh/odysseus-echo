package com.msp1974.vacompanion.odysseus.home.players

// Odysseus fork: the one process-wide holder of "music on another speaker", as Compose state.
// Driver (composed in HomeHost) polls Home Assistant every POLL_MS while the Echo's screen is in
// use and the setting is on; the clock page and the dashboard's now-playing card show [current]
// whenever OtherPlayersRules.showOther says so, and their buttons go to Home Assistant through
// [command]. Any failure keeps the last reading for a short while, then hides it.

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

object OtherPlayers {
    const val POLL_MS = 4_000L

    /** After a button press, read again this soon so the screen follows. */
    private const val AFTER_COMMAND_MS = 600L

    /** A reading older than this is dropped rather than shown when Home Assistant can't be reached. */
    private const val STALE_MS = 30_000L

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The other speaker shown when the Echo itself isn't playing; null = none. */
    var current by mutableStateOf<OtherPlayer?>(null)
        private set

    /** Media players seen with a track at the last read, for the settings menu. */
    var seen by mutableStateOf<List<HaMediaPlayer>>(emptyList())
        private set

    @Volatile private var lastSuccessMs = 0L
    private var lastLoggedKey = ""

    // The Driver run that may show results, 0 = none. A read that finishes after its run stopped
    // (a button's re-read, a slow HTTP call) must not bring a speaker back. Touched on the main thread.
    @Volatile private var activeRun = 0
    private var runs = 0

    private fun startRun(): Int {
        runs += 1
        activeRun = runs
        return runs
    }

    /** Ends [run] unless a newer run has already started (its cleanup can come late). */
    private fun stopRun(run: Int) {
        if (activeRun != run) return
        activeRun = 0
        clear()
    }

    /** What the clock and the card show: this Echo's own music, or another speaker's. */
    fun nowPlaying(own: SendspinNowPlaying): SendspinNowPlaying {
        val other = current?.nowPlaying
        return if (OtherPlayersRules.showOther(own, other)) other!! else own
    }

    /**
     * A control on the clock or the card was pressed: to Home Assistant when another speaker is
     * shown, else to the Echo's own Sendspin player through [sendspin].
     */
    fun command(own: SendspinNowPlaying, command: String, deviceManager: DeviceManager, sendspin: (String) -> Unit) {
        val other = current
        if (other == null || !OtherPlayersRules.showOther(own, other.nowPlaying)) {
            sendspin(command)
            return
        }
        val call = OtherPlayersRules.serviceCall(other.entityId, command) ?: return
        scope.launch {
            try {
                val (client, token) = session(deviceManager)
                client.send(token, call)
                Timber.i("Other players: %s on %s", command, other.entityId)
                delay(AFTER_COMMAND_MS)
                refresh(deviceManager)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Other players: %s on %s failed: %s", command, other.entityId, e.message)
            }
        }
    }

    fun clear() {
        current = null
        lastSuccessMs = 0L
    }

    /**
     * Reads Home Assistant once. Never throws (except cancellation). State is checked and written
     * on the main thread, where runs start and stop, so a stopped run can't be written to.
     */
    suspend fun refresh(deviceManager: DeviceManager) = withContext(Dispatchers.Main.immediate) { lock.withLock {
        if (activeRun == 0) return@withLock
        val nowMs = SystemClock.elapsedRealtime()
        try {
            val (client, token) = withContext(Dispatchers.IO) { session(deviceManager) }
            val snapshot = withContext(Dispatchers.IO) { client.snapshot(token) }
            // HA's "now" is read during the request, so the position is anchored to its end.
            val receivedMs = SystemClock.elapsedRealtime()
            // Turned off, locked or left the screen while waiting for Home Assistant.
            if (activeRun == 0) return@withLock
            val haBase = deviceManager.authenticationManager.getBaseUrl().toString().removeSuffix("/")
            seen = snapshot.players.filter { it.title.isNotBlank() || it.artist.isNotBlank() }
            val picked = OtherPlayersRules.pick(snapshot, deviceManager.config.area, HomeSettings.otherPlayersEntity)
            current = picked?.let { p ->
                val next = OtherPlayersRules.toNowPlaying(p, snapshot.haNow, receivedMs, haBase)
                val previous = current?.takeIf { it.entityId == p.entityId }?.nowPlaying
                OtherPlayer(p.entityId, p.name, OtherPlayersRules.settle(previous, next, receivedMs))
            }
            lastSuccessMs = nowMs
            val key = picked?.let { "${it.entityId} ${it.state} ${it.trackKey}" } ?: ""
            if (key != lastLoggedKey) {
                lastLoggedKey = key
                Timber.i("Other players: %s", picked?.let { "${it.entityId} ${it.state} (area '${it.area}')" } ?: "none")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Other players: refresh failed: %s", e.message)
            if (nowMs - lastSuccessMs > STALE_MS) current = null
        }
    } }

    private suspend fun session(deviceManager: DeviceManager): Pair<OtherPlayersClient, String> {
        val config = deviceManager.config
        if (config.refreshToken.isBlank()) throw OtherPlayersException("not signed in to Home Assistant")
        val auth = deviceManager.authenticationManager
        auth.ensureValidSession()
        val client = OtherPlayersClient(auth.getBaseUrl().toString().removeSuffix("/"))
        return client to config.accessToken
    }

    /**
     * Polls while the app is in front, the setting is on, something can show it ([shown]: the
     * clock or the dashboard's now-playing card), the network is up and the Echo isn't locked.
     * Composed once, in HomeHost, which is there whether or not the clock is turned on. Whatever
     * stops it (including the screen going blank, which takes HomeHost away) clears the speaker,
     * so an old song never shows when the screen comes back.
     */
    @Composable
    fun Driver(deviceManager: DeviceManager, networkConnected: Boolean, shown: Boolean) {
        val locked by LockMode.locked.collectAsState()
        val on = HomeSettings.otherPlayersEnabled && shown && networkConnected && !locked
        val chosen = HomeSettings.otherPlayersEntity
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(on, chosen, lifecycle) {
            if (!on) {
                activeRun = 0
                clear()
                return@LaunchedEffect
            }
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val run = startRun()
                try {
                    while (true) {
                        refresh(deviceManager)
                        delay(POLL_MS)
                    }
                } finally {
                    stopRun(run)
                }
            }
        }
    }
}
