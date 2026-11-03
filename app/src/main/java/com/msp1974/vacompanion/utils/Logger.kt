package com.msp1974.vacompanion.utils

import android.content.Context
import timber.log.Timber

class Logger {
    companion object {
        const val TAG = "ViewAssistCA"
    }
    fun d(message: String) {
        Timber.tag(TAG).d(message)
    }
    fun e(message: String) {
        Timber.tag(TAG).e(message)
    }
    fun i(message: String) {
        Timber.tag(TAG).i(message)
    }
    fun w(message: String) {
        Timber.tag(TAG).w(message)
    }
}

// Odysseus fork: telemetry removed. Upstream sends analytics events, crash reports and
// settings-change breadcrumbs to Google Firebase from here. This fork keeps the same API so
// call sites stay identical to upstream (easy rebases), but every method is a no-op and no
// Firebase/Google library is linked into the app. Nothing leaves the device from here.
@Suppress("UNUSED_PARAMETER")
class FirebaseManager private constructor() {
    companion object {
        private val instance = FirebaseManager()

        fun getInstance(context: Context? = null): FirebaseManager = instance

        const val DIAGNOSTIC_POPUP_SHOWN = "diagnostic_popup_shown"
        const val WAKE_WORD_DETECTED = "wake_word_detected"
        const val WAKE_WORD_ENGINE_STOP_TIMEOUT = "wake_word_engine_stop_timeout"
        const val WAKE_WORD_ENGINE_RESTART = "wake_word_engine_restart"
        const val WAKE_WORD_ENGINE_RESTART_GIVEN_UP = "wake_word_engine_restart_given_up"
        const val SATELLITE_ALREADY_RUNNING_MAIN = "satellite_already_running_main"
        const val RENDER_PROCESS_KILLED = "render_process_killed"
        const val RENDER_PROCESS_CRASHED = "render_process_crashed"
        const val MAIN_ACTIVITY_BACKGROUND_TASK_ALREADY_RUNNING = "main_background_task_already_running"
        const val TRIM_MEMORY_UI_HIDDEN = "trim_memory_ui_hidden"
        const val TRIM_MEMORY_BACKGROUND = "trim_memory_background"
        const val LOST_NETWORK = "lost_network"

    }

    fun setCustomKeys(keys: Map<String, Any>) {}

    fun logEvent(event: String, params: Map<String, String>) {}

    fun setUserProperty(key: String, value: String) {}

    fun addToCrashLog(message: String) {}

    fun logException(exception: Exception) {}
}
