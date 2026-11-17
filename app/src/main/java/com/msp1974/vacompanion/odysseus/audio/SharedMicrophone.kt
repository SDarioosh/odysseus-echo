package com.msp1974.vacompanion.odysseus.audio

import android.content.Context
import com.msp1974.vacompanion.audio.MicrophoneAudioStream
import com.msp1974.vacompanion.device.DeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber

/**
 * The app's one microphone, for the whole process (`docs/odysseus/shared-microphone.md`).
 *
 * Upstream made a [MicrophoneAudioStream] per `Satellite`, so the microphone could only be used
 * while Home Assistant was connected. Now there is one, created on first use and living as long
 * as the process; the satellite (wake word, listening pipeline, "Check my voice") uses it, and
 * so can anything else, Home Assistant connected or not.
 *
 * It still opens the microphone only while something collects [MicrophoneAudioStream.audio] or
 * [MicrophoneAudioStream.raw], and releases it once nothing does (screen-off battery).
 *
 * Use:
 * ```
 * val mic = SharedMicrophone.get(context, deviceManager)
 * mic.raw.collect { frame -> ... }   // 16 kHz mono PCM16, 1280 samples (80 ms) per frame,
 *                                    // before the gate, WebRTC, the suppressor and the gain
 * mic.audio                          // the processed frames the wake word and HA get
 * ```
 * Collect in a coroutine you cancel when done: collecting is what keeps the microphone open.
 * A collector that falls more than 16 frames (~1.3 s) behind loses the oldest; to keep every
 * frame, collect undispatched into your own channel, as `audio/DetectionBuffer` does.
 * Nothing is captured without RECORD_AUDIO: the flows then stay silent (no frames).
 */
object SharedMicrophone {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var stream: MicrophoneAudioStream? = null

    /** The process's microphone, created on the first call. */
    fun get(context: Context, deviceManager: DeviceManager): MicrophoneAudioStream =
        stream ?: synchronized(this) {
            stream ?: MicrophoneAudioStream(context.applicationContext, deviceManager, scope).also {
                Timber.d("Shared microphone created")
                stream = it
            }
        }

    /** Raw frames (before any processing) of the process's microphone; see [get]. */
    fun raw(context: Context, deviceManager: DeviceManager): SharedFlow<ShortArray> =
        get(context, deviceManager).raw
}
