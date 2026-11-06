package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: what the updater remembers across restarts, in its own SharedPreferences
// ("odysseus_update"): when it last checked, a failed download, and the version whose banner was
// dismissed on the clock.

import android.content.Context
import android.content.SharedPreferences

class UpdateStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("odysseus_update", Context.MODE_PRIVATE)

    var lastCheckMs: Long
        get() = prefs.getLong("last_check_ms", 0L)
        set(v) = prefs.edit().putLong("last_check_ms", v).apply()

    /** False when the last check failed or left an update waiting; it is then retried sooner. */
    var lastCheckSettled: Boolean
        get() = prefs.getBoolean("last_check_settled", false)
        set(v) = prefs.edit().putBoolean("last_check_settled", v).apply()

    var lastFailure: DownloadFailure?
        get() {
            val code = prefs.getLong("failed_code", 0L)
            return if (code > 0) DownloadFailure(code, prefs.getLong("failed_at_ms", 0L)) else null
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) remove("failed_code").remove("failed_at_ms")
                else putLong("failed_code", v.versionCode).putLong("failed_at_ms", v.atMs)
            }.apply()
        }

    /** The version code whose "Update ready" banner was dismissed on the clock page. */
    var dismissedCode: Long
        get() = prefs.getLong("dismissed_code", 0L)
        set(v) = prefs.edit().putLong("dismissed_code", v).apply()

    /** The version handed to Android's installer, to log the result after the restart. */
    var installingCode: Long
        get() = prefs.getLong("installing_code", 0L)
        set(v) = prefs.edit().putLong("installing_code", v).apply()
}
