package com.msp1974.vacompanion.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.audio.restartOnFailure // Odysseus fork
import com.msp1974.vacompanion.utils.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * The single owner of the microphone. [audio] is a hot flow of 16-bit PCM frames that any number
 * of subscribers (the wake word engine, an audio pipeline that is listening) can collect at once.
 *
 * The microphone is only open while there is at least one subscriber: it opens when the first
 * one subscribes and is released as soon as the last one leaves. It does not open at all if
 * RECORD_AUDIO has not been granted - subscribers then simply receive nothing.
 *
 * A subscriber that cannot keep up loses its oldest frames rather than stalling the microphone
 * for everyone else.
 */
class MicrophoneAudioStream(
    private val context: Context,
    private val deviceManager: DeviceManager,
    scope: CoroutineScope,
) {
    /** How loud the microphone has been lately, measured on the raw signal before any processing. */
    val level = MicLevelMonitor()

    /** Turns the microphone right down while a sound the device plays itself (the wake sound) is heard. */
    val suppressor = MicSuppressor()

    // Odysseus fork: each frame raw and processed, shared as two flows; either keeps the mic open (odysseus/audio/MicFrames.kt)
    // Odysseus fork: failures counted in a row, not over the process-wide stream's life (odysseus/audio/MicFrames.kt)
    private val frames = com.msp1974.vacompanion.odysseus.audio.MicFrameSharing(capture()
        .restartOnFailure(MAX_RESTARTS.toInt(), RESTART_DELAY_MS) { cause, n ->
            Timber.e(cause, "Microphone failed, restarting (attempt $n/$MAX_RESTARTS in a row)")
        }
        .catch { Timber.e(it, "Microphone failed, giving up until the next subscriber") },
        scope, BUFFER_FRAMES)

    // Processed frames: after the gate, WebRTC, the suppressor and the gain.
    val audio: SharedFlow<ShortArray> = frames.processed

    // Odysseus fork: the same frames before any processing (odysseus/audio/MicFrames.kt)
    val raw: SharedFlow<ShortArray> = frames.raw

    // Reading is blocking and cannot be interrupted, so it runs in its own coroutine and the
    // microphone is released from here, on cancellation, rather than by the read loop noticing it
    // was cancelled - releasing the AudioRecord is what unblocks a read that is in flight.
    @SuppressLint("MissingPermission")
    private fun capture(): Flow<com.msp1974.vacompanion.odysseus.audio.MicFrame> = channelFlow {
        if (!Permissions(context, deviceManager).hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Timber.w("Microphone not opened - RECORD_AUDIO permission not granted")
            return@channelFlow
        }
        level.reset()
        // Odysseus fork: the frame as read, before processing (odysseus/audio/MicFrames.kt)
        var raw: ShortArray? = null
        val microphone = MicrophoneInput(context, deviceManager, suppressor, level) { raw = it }
        try {
            withContext(Dispatchers.IO) { microphone.start() }
            Timber.d("Microphone opened")
            launch(Dispatchers.IO) {
                // Odysseus fork: a recorder that only fails to read is reopened (odysseus/audio/MicFrames.kt)
                val emptyReads = com.msp1974.vacompanion.odysseus.audio.EmptyReads(DEAD_AFTER_EMPTY_READS)
                while (isActive) {
                    raw = null
                    val frame = microphone.readShort()
                    if (emptyReads.dead(frame.isEmpty())) throw java.io.IOException("Microphone gives no audio")
                    if (frame.isNotEmpty()) trySend(com.msp1974.vacompanion.odysseus.audio.MicFrame(raw ?: frame.copyOf(), frame)) else delay(EMPTY_READ_DELAY_MS.milliseconds)
                }
            }
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                Timber.d("Microphone closing")
                microphone.close()
                Timber.d("Microphone closed")
            }
        }
    }

    private companion object {
        const val BUFFER_FRAMES = 16
        const val MAX_RESTARTS = 5L
        const val RESTART_DELAY_MS = 1000L
        const val EMPTY_READ_DELAY_MS = 10L
        const val DEAD_AFTER_EMPTY_READS = 200 // Odysseus fork: at least 2 s without audio
    }
}
