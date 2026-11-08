package com.msp1974.vacompanion.odysseus.lock

// Odysseus fork: Lock mode settings (Menu → Odysseus → Lock outside work hours). Stored on the
// device in their own SharedPreferences file, not sent to Home Assistant. Off by default; it can
// only be turned on once a PIN is set. The PIN is kept as a salted SHA-256 hash, never as typed.
// The PIN and lock overrides survive a restart. Bespoke fields over a PrefStore (the times are
// checked as a pair, `enabled` needs a PIN). See docs/odysseus/lock-mode.md.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.odysseus.settings.PrefStore
import java.security.SecureRandom

object LockSettings {
    private const val KEY_ENABLED = "lock_enabled"
    private const val KEY_OPEN = "lock_open_minute"
    private const val KEY_CLOSE = "lock_close_minute"
    private const val KEY_PIN_SALT = "lock_pin_salt"
    private const val KEY_PIN_HASH = "lock_pin_hash"
    private const val KEY_UNLOCKED_UNTIL = "lock_unlocked_until"
    private const val KEY_LOCKED_UNTIL = "lock_locked_until"
    private const val KEY_FAILURES = "lock_pin_failures"
    private const val KEY_LAST_FAILURE = "lock_pin_last_failure"

    private val store = PrefStore("odysseus_lock")

    private var enabledState by mutableStateOf(false)
    private var openState by mutableIntStateOf(LockRules.DEFAULT_OPEN_MINUTE)
    private var closeState by mutableIntStateOf(LockRules.DEFAULT_CLOSE_MINUTE)
    private var hasPinState by mutableStateOf(false)

    /** Loads the stored values. Safe to call more than once. */
    fun init(context: Context) = store.init(context) { p ->
        val open = p.getInt(KEY_OPEN, LockRules.DEFAULT_OPEN_MINUTE)
        val close = p.getInt(KEY_CLOSE, LockRules.DEFAULT_CLOSE_MINUTE)
        if (LockRules.validTimes(open, close)) { openState = open; closeState = close }
        hasPinState = p.getString(KEY_PIN_HASH, "").orEmpty().isNotEmpty()
        // Never on without a PIN: there would be no way to unlock it on the device.
        enabledState = p.getBoolean(KEY_ENABLED, false) && hasPinState
    }

    private fun prefs() = store.prefs

    /** Lock outside the work hours (default off). Can't be turned on without a PIN. */
    var enabled: Boolean
        get() = enabledState
        set(value) {
            enabledState = value && hasPinState
            prefs()?.edit()?.putBoolean(KEY_ENABLED, enabledState)?.apply()
        }

    val openMinute: Int get() = openState
    val closeMinute: Int get() = closeState

    /** Sets both times; refused (false) unless open comes before close. */
    fun setTimes(open: Int, close: Int): Boolean {
        if (!LockRules.validTimes(open, close)) return false
        openState = open; closeState = close
        prefs()?.edit()?.putInt(KEY_OPEN, open)?.putInt(KEY_CLOSE, close)?.apply()
        return true
    }

    val schedule: LockSchedule get() = LockSchedule(openState, closeState, LockRules.WEEKDAYS)

    val hasPin: Boolean get() = hasPinState

    /** Stores a new PIN (refused unless 4-8 digits). A new salt each time. */
    fun setPin(pin: String): Boolean {
        if (!LockRules.validPin(pin)) return false
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        prefs()?.edit()
            ?.putString(KEY_PIN_SALT, salt)
            ?.putString(KEY_PIN_HASH, LockRules.hashPin(salt, pin))
            ?.putInt(KEY_FAILURES, 0)
            ?.apply()
        hasPinState = true
        return true
    }

    fun pinMatches(pin: String): Boolean {
        val p = prefs() ?: return false
        return LockRules.pinMatches(p.getString(KEY_PIN_SALT, "").orEmpty(), p.getString(KEY_PIN_HASH, "").orEmpty(), pin)
    }

    var overrides: LockOverrides
        get() {
            val p = prefs() ?: return LockOverrides()
            return LockOverrides(p.getLong(KEY_UNLOCKED_UNTIL, 0L), p.getLong(KEY_LOCKED_UNTIL, 0L))
        }
        set(value) {
            prefs()?.edit()
                ?.putLong(KEY_UNLOCKED_UNTIL, value.unlockedUntilMs)
                ?.putLong(KEY_LOCKED_UNTIL, value.lockedUntilMs)
                ?.apply()
        }

    /** Wrong PINs in a row and when the last one was typed (wall clock), for the wait after too many. */
    val failures: Int get() = prefs()?.getInt(KEY_FAILURES, 0) ?: 0
    val lastFailureMs: Long get() = prefs()?.getLong(KEY_LAST_FAILURE, 0L) ?: 0L

    fun recordFailure(nowMs: Long) {
        prefs()?.edit()?.putInt(KEY_FAILURES, failures + 1)?.putLong(KEY_LAST_FAILURE, nowMs)?.apply()
    }

    fun clearFailures() {
        prefs()?.edit()?.putInt(KEY_FAILURES, 0)?.apply()
    }
}
