package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: this device's alarms from Odysseus (docs/odysseus/alarms-timers.md). The hub keeps
// them per device (set by voice: "wake me at 7"); this fetches the list and hands it to AlarmClock,
// which rings them on its own, so they ring when the hub or Home Assistant is down.
//
// It asks GET <base>/alarms?device_id=<id>:
//   - when the background task starts, and every hour;
//   - when the device feed has an "alarms" item (announce/Announcements.kt);
//   - when this device's voice turn set or cancelled one (an "alarm" action, TurnCard.kt).
// The menu's Delete sends DELETE <base>/alarms/<id>?device_id=<id> and asks again.
// Nothing is requested while the device isn't linked to Odysseus; the last list is kept.

import com.msp1974.vacompanion.odysseus.HubCall
import com.msp1974.vacompanion.odysseus.HubHttp
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.VoiceAction
import com.msp1974.vacompanion.odysseus.hubStatusText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

object Alarms {
    const val ACTION_KIND = "alarm"
    private const val EVERY_MS = 3_600_000L
    private const val RETRY_MS = 60_000L

    private var scope: CoroutineScope? = null
    private var deviceId: () -> String = { "" }
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized
    fun start(deviceId: () -> String) {
        this.deviceId = deviceId
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { run() }
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Ask the hub for the list now. */
    fun fetchNow(why: String) {
        Timber.d("Alarms: fetch ($why)")
        wake.trySend(Unit)
    }

    /** A voice turn of this device finished with these actions: ask again if one was an alarm. */
    fun onTurnActions(actions: List<VoiceAction>) {
        if (actions.any { it.kind == ACTION_KIND }) fetchNow("turn")
    }

    private suspend fun run() {
        while (scope?.isActive == true) {
            val ok = try {
                fetch(OdysseusLink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w("Alarms: ${OdysseusLink.failureText(e)}")
                false
            }
            withTimeoutOrNull(if (ok) EVERY_MS else RETRY_MS) { wake.receive() }
        }
    }

    /** One fetch; true when done or nothing to do (not linked), false to try again soon. */
    internal suspend fun fetch(http: HubHttp): Boolean {
        val id = deviceId()
        if (!OdysseusLink.isConfigured || id.isBlank()) return true
        val reply = http.request(HubCall(AlarmRules.listPath(id)))
        if (reply.code != 200) {
            Timber.w("Alarms: list answered ${hubStatusText(reply.code)}")
            return false
        }
        AlarmClock.setAlarms(AlarmRules.parse(reply.body))
        return true
    }

    /** Deletes [alarm] on the hub (the menu). Null when done, else what went wrong. */
    suspend fun delete(alarm: Alarm, http: HubHttp = OdysseusLink): String? {
        val id = deviceId()
        if (!OdysseusLink.isConfigured || id.isBlank()) return "Not linked to Odysseus"
        return try {
            val reply = http.request(HubCall(AlarmRules.deletePath(alarm.id, id), method = "DELETE"))
            if (reply.code in 200..299) {
                AlarmClock.setAlarms(AlarmClock.alarms.value.filter { it.id != alarm.id })
                fetchNow("deleted")
                null
            } else hubStatusText(reply.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OdysseusLink.failureText(e)
        }
    }
}
