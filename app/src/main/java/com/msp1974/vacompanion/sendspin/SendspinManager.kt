package com.msp1974.vacompanion.sendspin

import android.content.Context
import android.content.pm.PackageManager
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the Sendspin player's lifetime for as long as the background task runs, independent of
 * whether a Wyoming/HA satellite session is connected. Starts and stops it as the
 * [com.msp1974.vacompanion.settings.APPConfig.sendspinEnabled] setting changes, following the same
 * config-event-driven pattern as [com.msp1974.vacompanion.service.rtsp.RtspServerManager].
 */
class SendspinManager(
    private val context: Context,
    deviceManager: DeviceManager,
) : EventListener {

    private val config = deviceManager.config
    private var player: SendspinPlayer? = null
    private var advertiser: SendspinAdvertiser? = null

    // Odysseus fork: Lock mode stops the player while locked and starts it again after (odysseus/lock/).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lockJob: Job? = null

    fun start() {
        config.eventBroadcaster.addListener(this)
        if (config.sendspinEnabled && !LockMode.isLocked) startPlayer()
        lockJob?.cancel()
        lockJob = scope.launch {
            LockMode.locked.collect { locked ->
                if (locked) {
                    if (player != null) Timber.i("SendspinManager: stopping the player, locked")
                    stopPlayer()
                } else if (config.sendspinEnabled) {
                    startPlayer()
                }
            }
        }
    }

    // The background task only stops when the app or its service does, and the satellite comes
    // back with them, so tell Music Assistant to keep reconnecting (goodbye `restart`).
    fun shutdown() {
        lockJob?.cancel()
        lockJob = null
        config.eventBroadcaster.removeListener(this)
        stopPlayer(restarting = true)
    }

    @Synchronized
    private fun startPlayer() {
        if (player != null) return
        val playerName = "VACA ${config.uuid} Sendspin Player"
        val newPlayer = SendspinPlayer(
            context = context,
            config = config,
            playerName = playerName,
            softwareVersion = appVersion(),
        )
        if (!newPlayer.isRunning) {
            newPlayer.destroy()
            return
        }
        player = newPlayer
        advertiser = SendspinAdvertiser(
            context = context,
            serviceName = "vaca-sendspin-${config.uuid}",
            friendlyName = playerName,
        ).also { it.start(newPlayer.port) }
        config.sendspinPort = newPlayer.port
        config.sendspinRunning = true
    }

    @Synchronized
    private fun stopPlayer(restarting: Boolean = false) {
        advertiser?.stop()
        advertiser = null
        player?.destroy(restarting)
        player = null
        config.sendspinPort = 0
        config.sendspinRunning = false
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    // The setting is SharedPreferences-backed, so the event carries no typed value - read it back.
    override fun onEventTriggered(event: Event) {
        if (event.eventName != "sendspin_enabled") return
        Timber.d("SendspinManager: sendspin_enabled=${config.sendspinEnabled}")
        if (config.sendspinEnabled && !LockMode.isLocked) startPlayer() else stopPlayer()
    }
}
