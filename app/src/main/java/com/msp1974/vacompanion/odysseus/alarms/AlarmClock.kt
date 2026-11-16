package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: rings this device's alarms and timers (docs/odysseus/alarms-timers.md).
//
// Process-wide, started with the background task (service/BackgroundTask.kt), so it runs whether
// or not the activity is in front and whether or not Home Assistant is connected.
//   - Timers: Home Assistant's timer-* events, handed over by OdysseusSatellite.onServerPacket.
//   - Alarms: the hub's list for this device (Alarms.kt), kept here across restarts.
//   - A loop looks again at the next ring (at least every minute). Android's alarm clock
//     (AlarmManager.setAlarmClock, AlarmClockReceiver) is set for the same time as a backstop, so
//     a ring comes in time through doze, and starts the app if it was stopped.
//   - It rings with upstream's AlarmService (the alarm sound and alarm volume of the settings), so
//     upstream's own handling stops it too: a wake word or stop word while it sounds
//     (Satellite.onWakeWordDetected → handleStopWord). The loop sees the service gone and ends the
//     ring. The ringing card's Stop and Snooze (AlarmOverlays.kt) call [stop] / [snooze].
//   - Lock mode: nothing rings while locked (the ring is skipped and logged); locking stops a ring.

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import com.msp1974.vacompanion.odysseus.HubJson
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.players.AlarmService
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.time.ZoneId
import java.io.File

object AlarmClock {
    /** Sent on config.eventBroadcaster when something starts ringing; MainActivity turns the screen on. */
    const val EVENT_RINGING = "odysseusRinging"

    private const val PREFS = "odysseus_alarm_clock"
    private const val K_TIMERS = "timers"
    private const val K_ALARMS = "alarms"
    private const val K_RUNG = "rung"
    private const val K_SNOOZES = "snoozes"
    /** AlarmService starts a moment after startService; until then its absence isn't a stop. */
    private const val SERVICE_GRACE_MS = 3_000L
    private const val REQUEST_CODE = 0x0d55

    private val _timers = MutableStateFlow<List<DeviceTimer>>(emptyList())
    /** This device's timers (running and paused), soonest first. */
    val timers: StateFlow<List<DeviceTimer>> = _timers.asStateFlow()

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    /** This device's alarms, as the hub last gave them. */
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _ringing = MutableStateFlow<Due?>(null)
    /** What is ringing now, or null. */
    val ringing: StateFlow<Due?> = _ringing.asStateFlow()

    private var appContext: Context? = null
    private var config: APPConfig? = null
    private var prefs: SharedPreferences? = null
    private var scope: CoroutineScope? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Guarded by `this`.
    private var rung: Map<String, Long> = emptyMap()
    private var snoozes: List<Snooze> = emptyList()
    private var ringStartedMs = 0L

    /** Loads what was kept and starts the loop; once per process (later calls only update [config]). */
    @Synchronized
    fun start(context: Context, config: APPConfig) {
        this.config = config
        if (scope != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _timers.value = read(p, K_TIMERS, ListSerializer(DeviceTimer.serializer())) ?: emptyList()
        _alarms.value = p.getString(K_ALARMS, null)?.let { runCatching { AlarmRules.parse(it) }.getOrNull() } ?: emptyList()
        rung = read(p, K_RUNG, MapSerializer(String.serializer(), Long.serializer())) ?: emptyMap()
        snoozes = read(p, K_SNOOZES, ListSerializer(Snooze.serializer())) ?: emptyList()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch { loop() }
        // Locking stops a ring (nothing rings while locked).
        s.launch { LockMode.locked.drop(1).collect { locked -> if (locked && _ringing.value != null) stop("locked") } }
        Timber.i("Alarm clock: started (${_alarms.value.size} alarms, ${_timers.value.size} timers)")
    }

    /** Not stopped with the background task: a ring must not depend on it. Only for tests. */
    @Synchronized
    internal fun shutdown() {
        scope?.cancel()
        scope = null
    }

    /** Look again now (a list changed, or Android's alarm clock fired). */
    fun poke() { wake.trySend(Unit) }

    /** A Wyoming timer-* event from Home Assistant (OdysseusSatellite.onServerPacket). */
    fun onTimerEvent(type: String, data: JsonObject) {
        val now = System.currentTimeMillis()
        val result = synchronized(this) {
            TimerRules.apply(_timers.value, type, data, now).also { setTimers(it.timers) }
        }
        Timber.i("Alarm clock: $type ${data["id"]} (${result.timers.size} timers)")
        // HA says it has ended: ring now, unless this device already rang it at its own end time.
        result.finished?.let { finishTimer(it, now) }
        poke()
    }

    /** The hub's list for this device (Alarms.kt). */
    fun setAlarms(list: List<Alarm>) {
        synchronized(this) {
            if (list == _alarms.value) return
            _alarms.value = list
            prefs?.edit { putString(K_ALARMS, AlarmRules.encode(list)) }
        }
        Timber.i("Alarm clock: ${list.size} alarms")
        poke()
    }

    /** Stop the ring (the card's Stop). */
    fun stop(why: String = "stopped") {
        val due = _ringing.value ?: return
        _ringing.value = null
        stopSound()
        Timber.i("Alarm clock: ${due.key} $why")
        poke()
    }

    /** Stop the ring and ring again in RingRules.SNOOZE_MS (the card's Snooze). */
    fun snooze() {
        val due = _ringing.value ?: return
        synchronized(this) {
            val now = System.currentTimeMillis()
            snoozes = snoozes + RingRules.snooze(due, now, now)
            saveSnoozes()
        }
        stop("snoozed")
    }

    // --- the loop ---

    private suspend fun loop() {
        while (scope?.isActive == true) {
            val waitMs = try {
                tick(System.currentTimeMillis())
            } catch (e: Exception) {
                Timber.e(e, "Alarm clock: tick failed")
                30_000L
            }
            withTimeoutOrNull(waitMs.coerceIn(200L, 60_000L)) { wake.receive() }
        }
    }

    /** One look: ends a ring that stopped or ran too long, starts one that is due. Returns ms to wait. */
    private fun tick(now: Long): Long {
        val ctx = appContext ?: return 60_000L
        _ringing.value?.let { due ->
            if (now - ringStartedMs > RingRules.MAX_RING_MS) stop("rang for 10 min")
            // Stopped elsewhere: a wake word or stop word (Satellite.handleStopWord), Home Assistant.
            else if (now - ringStartedMs > SERVICE_GRACE_MS && AlarmService.sInstance == null) {
                _ringing.value = null
                Timber.i("Alarm clock: ${due.key} stopped on the device")
            } else return 1_000L
        }
        val upcoming = synchronized(this) {
            rung = RingRules.pruneRung(rung, now)
            dropOldTimers(now)
            RingRules.upcoming(_alarms.value, _timers.value, snoozes, rung.keys, now, ZoneId.systemDefault())
        }
        RingRules.dueNow(upcoming, now)?.let { due ->
            ring(ctx, due, now)
            return 1_000L
        }
        val next = RingRules.nextCheckMs(upcoming, now)
        scheduleBackstop(ctx, upcoming.firstOrNull { it.atMs > now }?.atMs)
        return next - now
    }

    private fun ring(ctx: Context, due: Due, now: Long) {
        synchronized(this) {
            rung = rung + (due.key to now)
            snoozes = snoozes.filter { it.key != due.key }
            if (due.kind == Due.Kind.TIMER) setTimers(_timers.value.filter { RingRules.timerKey(it.id) != due.key })
            prefs?.edit { putString(K_RUNG, HubJson.encodeToString(MapSerializer(String.serializer(), Long.serializer()), rung)) }
            saveSnoozes()
        }
        if (LockMode.isLocked) {
            Timber.i("Alarm clock: ${due.key} (${due.title}) not rung: locked")
            return
        }
        Timber.i("Alarm clock: ringing ${due.key} (${due.title})")
        ringStartedMs = now
        _ringing.value = due
        startSound(ctx)
        config?.eventBroadcaster?.notifyEvent(Event(EVENT_RINGING, "", due.key))
    }

    /** A timer HA says ended at [now]: rung at once unless already rung. */
    private fun finishTimer(t: DeviceTimer, now: Long) {
        synchronized(this) {
            if (RingRules.timerKey(t.id) in rung) return
            // Ring it through the loop: as a running timer that ends now.
            setTimers(_timers.value.filter { it.id != t.id } + t.copy(endsAtMs = now, pausedLeftMs = 0L))
        }
    }

    /** Running timers that ended more than RingRules.MISSED_MS ago won't ring: drop them. */
    private fun dropOldTimers(now: Long) {
        val keep = _timers.value.filter { it.endsAtMs == null || it.endsAtMs >= now - RingRules.MISSED_MS }
        if (keep.size != _timers.value.size) setTimers(keep)
    }

    private fun setTimers(list: List<DeviceTimer>) {
        _timers.value = list
        prefs?.edit { putString(K_TIMERS, HubJson.encodeToString(ListSerializer(DeviceTimer.serializer()), list)) }
    }

    private fun saveSnoozes() {
        prefs?.edit { putString(K_SNOOZES, HubJson.encodeToString(ListSerializer(Snooze.serializer()), snoozes)) }
    }

    private fun <T> read(p: SharedPreferences, key: String, s: kotlinx.serialization.KSerializer<T>): T? =
        p.getString(key, null)?.let { runCatching { HubJson.decodeFromString(s, it) }.getOrNull() }

    // --- sound ---

    @SuppressLint("UnsafeOptInUsageError")
    private fun startSound(ctx: Context) {
        try {
            ctx.startService(Intent(ctx, AlarmService::class.java).putExtra("uri", soundUri(ctx)))
        } catch (e: Exception) {
            Timber.e(e, "Alarm clock: can't start the alarm sound")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun stopSound() {
        val ctx = appContext ?: return
        runCatching { ctx.stopService(Intent(ctx, AlarmService::class.java)) }
    }

    /** The alarm sound chosen in the settings (as Satellite.handleAlarmAction), else the default. */
    private fun soundUri(ctx: Context): String {
        val c = config ?: return "asset:///alarm/default.mp3"
        val alarm = c.availableAlarms.find { it.id == c.alarmSound } ?: return "asset:///alarm/default.mp3"
        return if (alarm.custom) {
            File("${ctx.filesDir}/${CustomFileDownloader.CUSTOM_DIR}/${CustomFileDownloader.ALARMS_DIR}", alarm.filename).toUri().toString()
        } else "asset:///alarm/${alarm.filename}"
    }

    // --- Android's alarm clock, the backstop ---

    private var scheduledFor: Long? = null

    private fun scheduleBackstop(ctx: Context, atMs: Long?) {
        if (atMs == scheduledFor) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, Intent(ctx, AlarmClockReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (atMs == null) am.cancel(pi)
            else if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } else {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, null), pi)
            }
            scheduledFor = atMs
        } catch (e: Exception) {
            Timber.w(e, "Alarm clock: can't set Android's alarm clock")
        }
    }
}
