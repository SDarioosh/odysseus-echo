package com.msp1974.vacompanion.odysseus.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * One read from the microphone, twice: [raw] as the AudioRecord delivered it, and [processed]
 * after the gate, WebRTC (when on), the wake-sound suppressor and the gain - the audio the wake
 * word and Home Assistant get. Both are 16 kHz mono PCM16 of the same length (normally 1280
 * samples, 80 ms) and never share an array, so a subscriber may keep either.
 */
class MicFrame(val raw: ShortArray, val processed: ShortArray)

/**
 * Shares one microphone capture ([source]) as two hot flows, [processed] and [raw].
 *
 * Who keeps the microphone open: [source] is collected - the microphone open - while at least
 * one subscriber collects **either** flow. It starts with the first subscriber of either and is
 * cancelled (the microphone released) once the last subscriber of both has left. A subscriber of
 * [raw] alone keeps it open just as one of [processed] does.
 *
 * Each flow buffers [bufferFrames] frames per subscriber and drops the oldest when a subscriber
 * can't keep up, so a slow subscriber never stalls the microphone or the other subscribers.
 * Neither flow replays: a subscriber gets the frames read after it subscribed.
 *
 * Plain Kotlin, so the sharing is unit-tested (`MicFramesTest`); `audio/MicrophoneAudioStream`
 * feeds it the frames it reads.
 */
class MicFrameSharing(source: Flow<MicFrame>, scope: CoroutineScope, bufferFrames: Int) {

    // Runs [source] while either flow below has subscribers: each of them is subscribed to this
    // one only while it has subscribers of its own (WhileSubscribed), and this one runs [source]
    // only while it has a subscriber.
    private val frames: SharedFlow<MicFrame> = source
        .buffer(bufferFrames, BufferOverflow.DROP_OLDEST)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

    /** Frames after processing (what upstream's `MicrophoneAudioStream.audio` always carried). */
    val processed: SharedFlow<ShortArray> = share(scope, bufferFrames) { it.processed }

    /** The same frames before any processing. */
    val raw: SharedFlow<ShortArray> = share(scope, bufferFrames) { it.raw }

    // The buffer must be the last operator before shareIn: shareIn takes its buffering and
    // overflow behaviour from it.
    private fun share(scope: CoroutineScope, bufferFrames: Int, pick: (MicFrame) -> ShortArray) =
        frames.map(pick)
            .buffer(bufferFrames, BufferOverflow.DROP_OLDEST)
            .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)
}

/**
 * Collects this flow again after it failed, waiting [delayMs], until it has failed [maxInARow]
 * times **in a row**: an item delivered resets the count, so failures spread over days of
 * listening never add up (the shared microphone is collected for as long as the wake word runs).
 * Then it fails with that last failure. [onRestart] is told the cause and the count before each restart.
 */
fun <T> Flow<T>.restartOnFailure(
    maxInARow: Int,
    delayMs: Long,
    onRestart: (Throwable, Int) -> Unit = { _, _ -> },
): Flow<T> = flow {
    var failures = 0
    emitAll(
        this@restartOnFailure
            .onEach { failures = 0 }
            .retryWhen { cause, _ ->
                if (cause is CancellationException || failures >= maxInARow) {
                    false
                } else {
                    failures++
                    onRestart(cause, failures)
                    delay(delayMs)
                    true
                }
            },
    )
}

/**
 * Tells a dead microphone from a slow one: [dead] is true after [limit] empty reads in a row.
 * AudioRecord.read reports a lost recorder (ERROR_DEAD_OBJECT, the audio server restarted) by
 * returning an error, never by throwing, so without this the stream would read nothing for good.
 */
class EmptyReads(private val limit: Int) {
    private var inARow = 0

    fun dead(empty: Boolean): Boolean {
        inARow = if (empty) inARow + 1 else 0
        return inARow >= limit
    }
}
