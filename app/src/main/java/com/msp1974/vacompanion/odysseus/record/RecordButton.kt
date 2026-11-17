package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the Echo Show's privacy button starts recordings (docs/odysseus/recording.md,
// "The privacy button"; docs/odysseus/echo-show-5.md, "Privacy button"). Setting
// Menu → Odysseus → Recording → Privacy button: Record (default) or Mute (upstream behaviour).
//
// Two inputs, both fed to RecordButtonGestures (pure, unit-tested), all on the main thread:
//   - onKeyEvent(): MainActivity.dispatchKeyEvent's hook. The button's KEY_POWER (scancode 116) is
//     mapped to MEDIA_RECORD by scripts/odysseus/gpio-privacy-button.kl. Gives press and hold, but
//     only reaches the focused activity while the display is on.
//   - the microphone-mute broadcast: every press flips Android's mic-mute flag (SW_MUTE_DEVICE),
//     with the display off or another app in front too. Counts as a press when no key covers it.
// While the button records, Android's mic-mute flag is ignored (no "Microphone off", no refusal):
// on this device it doesn't cut the microphone, and every press flips it. Software mute (quick
// actions "Mic Mute", config.isMuted) still mutes and still stops recordings.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.msp1974.vacompanion.odysseus.VoiceActivity
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import timber.log.Timber

object RecordButton {
    const val HINT = "The privacy button now records (hold 1 s for a voice note). To mute, use Mic Mute in the quick actions."

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val gestures = RecordButtonGestures()
    private val holdTimer = Runnable { run(gestures.holdTimerFired(SystemClock.uptimeMillis()), "hold") }
    private val switchTimer = Runnable { run(gestures.switchTimerFired(SystemClock.uptimeMillis()), "switch") }

    @Volatile private var audio: AudioManager? = null
    @Volatile private var config: APPConfig? = null
    @Volatile private var hasButton: Boolean? = null
    private var lastRawMute = false

    private val muteReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = onMuteChanged()
    }

    /** Called once from RecorderController.install (the foreground service). */
    fun install(context: Context, config: APPConfig) {
        if (audio != null) return
        val app = context.applicationContext
        this.config = config
        hasPrivacyButton(app)
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio = am
        lastRawMute = am.isMicrophoneMute
        // On the main thread, like the key events, so the gesture logic needs no lock.
        app.registerReceiver(muteReceiver, IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED), null, handler)
        handler.post { refreshMute() }
        Timber.i("Privacy button: present=${hasButton}, mode=${RecorderSettings.privacyButton}, records=$isRecordButton")
    }

    /** Whether this device has the Echo Show's privacy button (by input device name). Cached. */
    fun hasPrivacyButton(context: Context): Boolean {
        hasButton?.let { return it }
        val im = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val names = mutableListOf<String>()
        if (im != null) for (id in im.inputDeviceIds) im.getInputDevice(id)?.name?.let { names += it }
        return PrivacyButtonRules.hasPrivacyButton(names).also { hasButton = it }
    }

    /** The button records now (it exists, is set to Record, and recording is on). */
    val isRecordButton: Boolean
        get() = PrivacyButtonRules.isRecordButton(hasButton == true, RecorderSettings.privacyButton, RecorderSettings.enabled)

    /** Android's mic-mute flag as the app should see it (ListeningIndicator, the recorder). */
    fun effectiveMicMute(rawMuted: Boolean): Boolean = PrivacyButtonRules.systemMuteCounts(rawMuted, isRecordButton)

    /** Re-reads the mute state for the app; call after the mode or "Recording" changed. */
    fun refreshMute() {
        gestures.reset()
        handler.removeCallbacks(holdTimer)
        handler.removeCallbacks(switchTimer)
        audio?.let { VoiceActivity.setMicMuted(effectiveMicMute(it.isMicrophoneMute)) }
    }

    /**
     * MainActivity.dispatchKeyEvent's hook. True when the event is the privacy button's key: it's
     * consumed in both modes (in Mute mode the button mutes through its switch, as before, and the
     * key must not reach the media session as MEDIA_RECORD).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MEDIA_RECORD) return false
        if (event.device?.name != PrivacyButtonRules.KEY_DEVICE) return false
        if (!isRecordButton) return true
        val now = SystemClock.uptimeMillis()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> run(gestures.keyDown(now, event.repeatCount), "key down")
            KeyEvent.ACTION_UP -> run(gestures.keyUp(now), "key up")
        }
        return true
    }

    private fun onMuteChanged() {
        val am = audio ?: return
        val raw = am.isMicrophoneMute
        VoiceActivity.setMicMuted(effectiveMicMute(raw))
        if (raw == lastRawMute) return
        lastRawMute = raw
        if (isRecordButton) run(gestures.switchChanged(SystemClock.uptimeMillis()), "switch")
    }

    private fun run(actions: List<ButtonAction>, source: String) {
        for (a in actions) {
            when (a) {
                ButtonAction.Press -> {
                    Timber.i("Privacy button: press ($source)")
                    hintOnce()
                    RecorderController.press()
                }
                ButtonAction.Hold -> {
                    Timber.i("Privacy button: hold ($source)")
                    hintOnce()
                    RecorderController.hold()
                }
                is ButtonAction.StartHoldTimer -> {
                    handler.removeCallbacks(holdTimer)
                    handler.postDelayed(holdTimer, a.delayMs)
                }
                is ButtonAction.StartSwitchTimer -> {
                    handler.removeCallbacks(switchTimer)
                    handler.postDelayed(switchTimer, a.delayMs)
                }
            }
        }
    }

    /** The first time the button records, say once that it no longer mutes. */
    private fun hintOnce() {
        if (RecorderSettings.privacyHintShown) return
        RecorderSettings.privacyHintShown = true
        config?.eventBroadcaster?.notifyEvent(Event("showToastMessage", "", HINT))
    }
}
