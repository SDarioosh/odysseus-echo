package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: one recording or voice note being captured (docs/odysseus/recording.md). Listens
// to the shared microphone's raw frames (odysseus/audio/SharedMicrophone, before any processing)
// undispatched into its own channel, as audio/DetectionBuffer does, so no frame is lost to a slow
// encoder. A background coroutine raises quiet audio (RecordingGain), cuts it into 30 s chunks
// (ChunkAssembler), encodes each as 16 kHz mono WAV and writes it to the queue. finish() stops
// listening; the rest is written, then the session is marked finished. Created by RecorderController as an anonymous object.

import com.msp1974.vacompanion.odysseus.Wav
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

abstract class RecordingCapture(
    val kind: RecordingKind,
    val sessionId: String,
    val startedAtMs: Long,
    private val dir: File,
    private val queue: ChunkQueue,
    private val microphone: Flow<ShortArray>,
    private val scope: CoroutineScope,
    encodeDispatcher: CoroutineDispatcher,
) {
    /** A chunk was written to the queue (it can be uploaded now). */
    abstract fun onChunkWritten(sequence: Int)

    /** Every chunk is written and the session is marked finished. */
    abstract fun onFinished(chunks: Int)

    private val channel = Channel<ShortArray>(BUFFER_FRAMES, BufferOverflow.DROP_OLDEST)
    private val assembler = ChunkAssembler(startedAtMs)
    private val gain = RecordingGain()
    private val kindName = if (kind == RecordingKind.NOTE) RecordingUploader.KIND_NOTE else RecordingUploader.KIND_RECORDING

    @Volatile var lastFrameAtMs: Long = startedAtMs
        private set

    @Volatile private var finished = false
    private var listener: Job = listen()

    private val writer: Job = scope.launch(encodeDispatcher) {
        for (frame in channel) {
            for (chunk in assembler.add(gain.process(frame))) write(chunk)
        }
        assembler.finish()?.let { write(it) }
        val chunks = assembler.chunks
        runCatching { queue.finishSession(dir, chunks) }
            .onFailure { Timber.e(it, "Recorder: could not mark session $sessionId finished") }
        Timber.i("Recorder: $kindName $sessionId finished, $chunks chunk(s)")
        onFinished(chunks)
    }

    // Undispatched, so the microphone is listened to when the constructor returns.
    private fun listen(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        microphone.collect {
            lastFrameAtMs = System.currentTimeMillis()
            channel.trySend(it)
        }
    }

    /**
     * Listens again: the shared microphone gives up after repeated failures until all its
     * listeners have left (docs/odysseus/shared-microphone.md). Synchronized with [finish]: the
     * ticker calls it outside the recorder's lock, and a listener started after finish() would
     * keep the microphone open for good.
     */
    @Synchronized
    fun resubscribe() {
        if (finished) return
        Timber.w("Recorder: no microphone audio for a while; listening again")
        listener.cancel()
        lastFrameAtMs = System.currentTimeMillis()
        listener = listen()
    }

    /** Stops listening; what was heard is still written. */
    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        listener.cancel()
        channel.close()
    }

    private fun write(chunk: AudioChunk) {
        try {
            val info = ChunkInfo(sessionId, chunk.sequence, kindName, ChunkAssembler.isoUtc(chunk.startMs))
            queue.writeChunk(dir, info, Wav.encodePcm16Mono(chunk.pcm, ChunkAssembler.SAMPLE_RATE))
            onChunkWritten(chunk.sequence)
        } catch (e: Exception) {
            // Storage full or gone: the chunk is lost, the session goes on (its sequence has a gap
            // the hub will see; better than stopping a recording someone relies on).
            Timber.e(e, "Recorder: could not write chunk ${chunk.sequence} of $sessionId")
        }
    }

    companion object {
        /** About 2.7 min of 80 ms frames waiting for the encoder before the oldest is dropped. */
        const val BUFFER_FRAMES = 2_000
    }
}
