package com.msp1974.vacompanion.odysseus.power

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

// Odysseus fork: the one owner of the app's Wi-Fi lock. Upstream held a high-performance lock for
// the app's whole life (utils/Network.kt, from ui/VAViewModel.kt), so the radio never power-saved.
// Here the lock is held only while a [WifiNeed] is on; [WifiLockPolicy] decides, this applies it.
// Started and stopped with the background task (service/BackgroundTask.kt).
//
// Who sets which need:
// - VOICE: odysseus/OdysseusSatellite.kt, from the wake word (or a pipeline starting) while the
//   satellite is arbitrating or has a pipeline ([holdWhile]).
// - ARBITRATION_MASTER: broadcasts/VacaUdpBroadcast.kt, while it is master and other devices are around.
// - SENDSPIN, CAMERA: here, from the config events the player and the RTSP server already send.
object WifiPerformance : EventListener {
    private val policy = WifiLockPolicy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchers = HashMap<WifiNeed, Job>()
    private val predicates = HashMap<WifiNeed, () -> Boolean>()
    private val lastAsked = HashMap<WifiNeed, Long>()
    private var locks: List<WifiManager.WifiLock> = emptyList()
    private var held = false
    private var expiryJob: Job? = null
    private var config: APPConfig? = null

    private fun now() = SystemClock.elapsedRealtime()

    @Suppress("DEPRECATION") // HIGH_PERF: the only mode with the screen off before Android 14
    @Synchronized
    fun start(context: Context, config: APPConfig) {
        if (this.config != null) return
        this.config = config
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locks = WifiLockPolicy.lockModes(Build.VERSION.SDK_INT).map { mode ->
            val platformMode = when (mode) {
                WifiLockMode.HIGH_PERF -> WifiManager.WIFI_MODE_FULL_HIGH_PERF
                WifiLockMode.LOW_LATENCY -> WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            }
            wifi.createWifiLock(platformMode, "vaca:wifi:${mode.name.lowercase()}").apply {
                setReferenceCounted(false)
            }
        }
        config.eventBroadcaster.addListener(this)
        apply()
    }

    @Synchronized
    fun stop() {
        config?.eventBroadcaster?.removeListener(this)
        config = null
        watchers.values.forEach { it.cancel() }
        watchers.clear()
        predicates.clear()
        lastAsked.clear()
        expiryJob?.cancel()
        WifiNeed.entries.forEach { policy.set(it, false, now()) }
        release()
        locks = emptyList()
    }

    /** Turns [need] on or off. Taking the lock happens before this returns. */
    @Synchronized
    fun set(need: WifiNeed, active: Boolean) {
        policy.set(need, active, now())
        apply()
    }

    /**
     * Turns [need] on now and keeps it on while [active] stays true, checked every [pollMs]; then
     * off (it lingers for its grace). Calling it again while it is held just keeps it.
     */
    @Synchronized
    fun holdWhile(need: WifiNeed, pollMs: Long = 1_000, active: () -> Boolean) {
        set(need, true)
        lastAsked[need] = now()
        predicates[need] = active
        if (watchers[need]?.isActive == true) return
        watchers[need] = scope.launch {
            while (true) {
                delay(pollMs)
                synchronized(this@WifiPerformance) {
                    // Asked again within the last poll: the caller may not have set its state yet.
                    val recent = now() - (lastAsked[need] ?: 0L) < pollMs
                    if (recent || predicates[need]?.invoke() == true) {
                        set(need, true) // refreshes it, so a long but genuine need isn't dropped by its limit
                    } else {
                        set(need, false)
                        watchers.remove(need)
                        predicates.remove(need)
                        return@launch
                    }
                }
            }
        }
    }


    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "rtspClientCount" -> set(WifiNeed.CAMERA, (event.newValue as? Int ?: 0) > 0)
            // sendspin/SendspinPlayer.kt sets config.sendspinPlaying while its stream runs.
            "sendspinPlaybackStarted" -> holdWhile(WifiNeed.SENDSPIN, 2_000) { config?.sendspinPlaying == true }
        }
    }

    @Synchronized
    private fun apply() {
        val now = now()
        val reasons = policy.reasons(now)
        val want = reasons.isNotEmpty() && locks.isNotEmpty()
        if (want != held) {
            if (want) {
                try {
                    locks.forEach { if (!it.isHeld) it.acquire() }
                    held = true
                    Timber.i("Wi-Fi: full performance for ${reasons.joinToString()}")
                } catch (e: Exception) {
                    Timber.w("Wi-Fi: could not take the lock: ${e.message}")
                }
            } else {
                release()
                Timber.i("Wi-Fi: power save allowed")
            }
        }
        expiryJob?.cancel()
        val next = policy.nextChangeAt(now) ?: return
        expiryJob = scope.launch {
            delay((next - now).coerceAtLeast(1))
            apply()
        }
    }

    private fun release() {
        locks.forEach { runCatching { if (it.isHeld) it.release() } }
        held = false
    }
}
