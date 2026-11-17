package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: cuts one session's microphone frames into 30 s chunks for Athena
// (docs/odysseus/recording.md). Plain Kotlin. A chunk's timestamp is the session start plus the
// audio before it (80 ms per 1280-sample frame at 16 kHz), not the wall clock, so chunks join
// without gaps or overlaps however late the encoder runs.

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One chunk ready to be written: [pcm] is 16 kHz mono PCM16. */
class AudioChunk(val sequence: Int, val startMs: Long, val pcm: ShortArray)

class ChunkAssembler(
    private val sessionStartMs: Long,
    private val chunkSamples: Int = CHUNK_SAMPLES,
    private val sampleRate: Int = SAMPLE_RATE,
) {
    private var buffer = ShortArray(chunkSamples)
    private var filled = 0
    private var samplesBefore = 0L   // samples in the chunks already returned
    private var nextSequence = 0

    /** Chunks handed out so far. */
    val chunks: Int get() = nextSequence

    /** Adds a frame; returns the chunks it completed (usually none, at most one per 30 s). */
    fun add(frame: ShortArray): List<AudioChunk> {
        var out: MutableList<AudioChunk>? = null
        var offset = 0
        while (offset < frame.size) {
            val n = minOf(frame.size - offset, chunkSamples - filled)
            System.arraycopy(frame, offset, buffer, filled, n)
            filled += n
            offset += n
            if (filled == chunkSamples) {
                (out ?: mutableListOf<AudioChunk>().also { out = it }).add(take())
            }
        }
        return out ?: emptyList()
    }

    /** The last, shorter chunk, or null when nothing is left. */
    fun finish(): AudioChunk? = if (filled == 0) null else take()

    private fun take(): AudioChunk {
        val pcm = if (filled == buffer.size) buffer else buffer.copyOf(filled)
        val chunk = AudioChunk(nextSequence++, startOf(samplesBefore), pcm)
        samplesBefore += filled
        buffer = ShortArray(chunkSamples)
        filled = 0
        return chunk
    }

    private fun startOf(samples: Long): Long = sessionStartMs + samples * 1000L / sampleRate

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 30 s: 960 KB of WAV, under the hub relay's 5 MiB limit. */
        const val CHUNK_SAMPLES = SAMPLE_RATE * 30

        private val ISO_MS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** ISO-8601 UTC with milliseconds, as contract C4 wants: 2026-11-06T09:15:00.080Z. */
        fun isoUtc(epochMs: Long): String = ISO_MS.format(Instant.ofEpochMilli(epochMs))
    }
}
