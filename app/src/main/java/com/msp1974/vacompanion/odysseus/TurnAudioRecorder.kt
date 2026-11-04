package com.msp1974.vacompanion.odysseus

// Odysseus fork: keeps a copy of the audio a voice turn streams to Home Assistant, so the device
// can send the spoken command to Odysseus as well (the speaker check, POST /satellite/turn).
//
// It is fed by a tee on the pipeline's microphone flow (OdysseusTurn.tee), so it holds exactly
// the frames the pipeline sent: the audio kept since the wake word (DetectionBuffer), then live
// audio, until the pipeline stops listening. Only the first maxSeconds are kept. The audio lives
// in memory for one turn only and is never written to storage.

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.io.ByteArrayOutputStream

class TurnAudioRecorder(
    val sampleRate: Int = 16_000,
    maxSeconds: Int = MAX_SECONDS,
) {
    private val maxSamples = sampleRate * maxSeconds
    private val buffer = ShortArray(maxSamples)
    private var count = 0
    @Volatile private var closed = false

    /** Samples kept so far. */
    val samples: Int get() = synchronized(this) { count }

    /** Wraps a microphone flow so every frame it emits is also kept here. */
    fun tee(frames: Flow<ShortArray>): Flow<ShortArray> = frames.onEach { add(it) }

    fun add(frame: ShortArray) {
        if (closed) return
        synchronized(this) {
            val n = minOf(frame.size, maxSamples - count)
            if (n <= 0) return
            System.arraycopy(frame, 0, buffer, count, n)
            count += n
        }
    }

    /** Stops keeping audio and returns what was kept as a WAV file, or null if there is none. */
    fun finish(): ByteArray? {
        closed = true
        val pcm = synchronized(this) { buffer.copyOf(count) }
        return if (pcm.isEmpty()) null else Wav.encodePcm16Mono(pcm, sampleRate)
    }

    /** Drops the audio without using it. */
    fun discard() {
        closed = true
        synchronized(this) { count = 0 }
    }

    companion object {
        /** The hub accepts up to 15 s (voice contract §4). */
        const val MAX_SECONDS = 15
    }
}

/** A minimal RIFF/WAVE writer for 16-bit little-endian PCM. */
object Wav {
    fun encodePcm16Mono(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        fun int32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun int16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int32(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int32(16)              // fmt chunk size
        int16(1)               // PCM
        int16(1)               // mono
        int32(sampleRate)
        int32(sampleRate * 2)  // byte rate
        int16(2)               // block align
        int16(16)              // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        int32(dataBytes)
        for (s in pcm) int16(s.toInt())
        return out.toByteArray()
    }
}
