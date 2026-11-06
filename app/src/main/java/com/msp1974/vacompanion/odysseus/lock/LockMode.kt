package com.msp1974.vacompanion.odysseus.lock

// Odysseus fork: Lock mode at run time. Installed once from VACAApplication. Decides every 15 s
// (and at once after any change) whether the device is locked, from LockSettings and LockRules, and
// publishes it as [locked]. While locked:
//  - LockOverlay covers the whole screen (dashboard, clock, settings menu) with a clock and a PIN pad;
//  - Satellite mutes the wake word engine (which lets go of the microphone), ignores Home
//    Assistant's wake / media / alarm / toast actions, drops announcements (answering "played") and
//    refuses conversations Home Assistant starts;
//  - SendspinManager stops the Sendspin player, RecorderController stops and refuses recording.
// Home Assistant locks and unlocks it with vaca.send_event, event navigate, url odysseus://lock or
// odysseus://unlock (handled in Satellite.handleAction). See docs/odysseus/lock-mode.md.

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object LockMode {
    private const val TICK_MS = 15_000L
    private const val REMOTE_LOCK = "odysseus://lock"
    private const val REMOTE_UNLOCK = "odysseus://unlock"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var installed = false

    private val _locked = MutableStateFlow(false)
    /** True while the device is locked. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    val isLocked: Boolean get() = _locked.value

    private val _until = MutableStateFlow<Long?>(null)
    /** When the current state changes by itself (epoch ms), or null. */
    val until: StateFlow<Long?> = _until.asStateFlow()

    fun install(context: Context) {
        if (installed) return
        installed = true
        LockSettings.init(context)
        evaluate("start")
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                evaluate("tick")
            }
        }
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())

    /** Works out the state again; call after any change to the settings. */
    @Synchronized
    fun evaluate(why: String) {
        val t = now()
        val nowMs = t.toInstant().toEpochMilli()
        val stored = LockSettings.overrides
        val o = LockRules.expire(stored, nowMs)
        if (o != stored) LockSettings.overrides = o
        val d = LockRules.decide(LockSettings.enabled, LockSettings.schedule, o, t)
        _until.value = d.untilMs
        if (d.locked != _locked.value) {
            Timber.i("Lock mode: ${if (d.locked) "locked" else "unlocked"} ($why), until ${label(d.untilMs)}")
            _locked.value = d.locked
        }
    }

    enum class PinResult { OK, WRONG, WAIT, NO_PIN }

    /** Tries [pin]; on success unlocks until the next scheduled lock time. */
    @Synchronized
    fun unlockWithPin(pin: String): PinResult {
        val result = checkPin(pin)
        if (result != PinResult.OK) return result
        LockSettings.overrides = LockRules.unlock(LockSettings.schedule, now())
        evaluate("PIN")
        return PinResult.OK
    }

    /** Checks [pin] without unlocking (the settings menu asks for it before a change). Wrong PINs count. */
    @Synchronized
    fun checkPin(pin: String): PinResult {
        if (!LockSettings.hasPin) return PinResult.NO_PIN
        if (waitRemainingMs() > 0) return PinResult.WAIT
        if (!LockSettings.pinMatches(pin)) {
            LockSettings.recordFailure(System.currentTimeMillis())
            Timber.w("Lock mode: wrong PIN (${LockSettings.failures} in a row)")
            return PinResult.WRONG
        }
        LockSettings.clearFailures()
        return PinResult.OK
    }

    /** Milliseconds before another PIN may be tried. */
    fun waitRemainingMs(): Long =
        LockRules.waitRemaining(LockSettings.failures, LockSettings.lastFailureMs, System.currentTimeMillis())

    /** Locks until the schedule next opens ("Lock now" or Home Assistant). Only while Lock mode is on. */
    @Synchronized
    fun lockNow(source: String) {
        if (!LockSettings.enabled) {
            Timber.w("Lock mode: lock from $source ignored, Lock mode is off")
            return
        }
        LockSettings.overrides = LockRules.lock(LockSettings.schedule, now())
        evaluate(source)
    }

    /** Unlocks until the next scheduled lock time (Home Assistant). */
    @Synchronized
    fun unlockNow(source: String) {
        if (!LockSettings.enabled) return
        LockSettings.overrides = LockRules.unlock(LockSettings.schedule, now())
        evaluate(source)
    }

    /** Lock mode turned on or off in the menu: forget any lock or unlock still holding. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        LockSettings.overrides = LockOverrides()
        LockSettings.enabled = value
        evaluate(if (value) "turned on" else "turned off")
    }

    /**
     * Home Assistant's `navigate` action, when it carries a lock command. True if [url] was one
     * (then it must not reach the dashboard), false for any other URL.
     */
    fun handleRemote(url: String): Boolean {
        when (url.trim().lowercase(Locale.ROOT)) {
            REMOTE_LOCK -> lockNow("Home Assistant")
            REMOTE_UNLOCK -> unlockNow("Home Assistant")
            else -> return false
        }
        return true
    }

    /** "Mon 08:00" for a time this week, "08:00" for today. */
    fun label(ms: Long?): String {
        if (ms == null) return "no set time"
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        val today = now().toLocalDate()
        val time = t.format(DateTimeFormatter.ofPattern("HH:mm"))
        return if (t.toLocalDate() == today) time
        else t.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + " " + time
    }
}
