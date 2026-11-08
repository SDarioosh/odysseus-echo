package com.msp1974.vacompanion.odysseus.home.players

// Odysseus fork: the one process-wide holder of "music on another speaker", as Compose state.
// Driver (composed in HomeHost) polls Home Assistant through HaClient (POST /api/template) every
// POLL_MS while the Echo's screen is in use and the setting is on; the clock page and the
// dashboard's now-playing card show [current] whenever OtherPlayersRules.showOther says so, and
// their buttons go to Home Assistant through [command] (a media_player call). Any failure keeps
// the last reading for a short while, then hides it.

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
import com.msp1974.vacompanion.odysseus.ha.HaClient
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

object OtherPlayers {
    const val POLL_MS = 4_000L

    /** After a button press, read again this soon so the screen follows. */
    private const val AFTER_COMMAND_MS = 600L

    /** A reading older than this is dropped rather than shown when Home Assistant can't be reached. */
    private const val STALE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The other speaker shown when the Echo itself isn't playing; null = none. */
    var current by mutableStateOf<OtherPlayer?>(null)
        private set

    /** Media players seen with a track at the last read, for the settings menu. */
    var seen by mutableStateOf<List<HaMediaPlayer>>(emptyList())
        private set

    // The polling run, the only writer of [current]. Touched on the main thread only.
    private var polling: Job? = null

    /** A button was pressed: the run reads again at once instead of waiting out POLL_MS. */
    private val reread = Channel<Unit>(Channel.CONFLATED)

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
        val call = OtherPlayersRules.commandCall(other.entityId, command) ?: return
        scope.launch {
            try {
                HaClient.get(deviceManager).call(call.domain, call.service, call.data)
                Timber.i("Other players: %s on %s", command, other.entityId)
                delay(AFTER_COMMAND_MS)
                reread.trySend(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Other players: %s on %s failed: %s", command, other.entityId, e.message)
            }
        }
    }

    fun clear() { current = null }

    /**
     * Reads Home Assistant every POLL_MS (sooner after a button press) until cancelled, then
     * clears the speaker. Runs on the main thread (the Driver's effect), so state is written
     * where Compose reads it; HaApi's I/O runs elsewhere and a cancelled read returns nothing,
     * so a run that was stopped writes nothing more. A new run first waits for the one before it
     * to end (its HTTP call can't be interrupted), so that run's cleanup can't clear this one.
     */
    private suspend fun poll(deviceManager: DeviceManager) {
        val previous = polling
        polling = currentCoroutineContext().job
        previous?.cancelAndJoin()
        reread.tryReceive() // a press from before this run: it reads now anyway
        val ha = HaClient.get(deviceManager)
        var lastSuccessMs = 0L
        var lastLoggedKey = ""
        try {
            while (true) {
                val nowMs = SystemClock.elapsedRealtime()
                try {
                    val snapshot = OtherPlayersRules.snapshot(ha)
                    // HA's "now" is read during the request, so the position is anchored to its end.
                    val receivedMs = SystemClock.elapsedRealtime()
                    val haBase = deviceManager.authenticationManager.getBaseUrl().toString().removeSuffix("/")
                    seen = snapshot.players.filter { it.title.isNotBlank() || it.artist.isNotBlank() }
                    val picked = OtherPlayersRules.pick(snapshot, deviceManager.config.area, HomeSettings.otherPlayersEntity)
                    current = picked?.let { p ->
                        val next = OtherPlayersRules.toNowPlaying(p, snapshot.haNow, receivedMs, haBase)
                        val previousShown = current?.takeIf { it.entityId == p.entityId }?.nowPlaying
                        OtherPlayer(p.entityId, p.name, OtherPlayersRules.settle(previousShown, next, receivedMs))
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
                withTimeoutOrNull(POLL_MS) { reread.receive() }
            }
        } finally {
            clear()
        }
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
                // The run before was cancelled with the old effect and writes nothing more.
                clear()
                return@LaunchedEffect
            }
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { poll(deviceManager) }
        }
    }
}
