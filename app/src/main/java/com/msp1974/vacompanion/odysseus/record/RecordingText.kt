package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's on-screen wording, plain Kotlin so it is unit-tested.

object RecordingText {
    /** "0:42", "12:34", "1:02:03". */
    fun elapsed(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The pill: "REC 12:34" or "NOTE 0:42"; null when nothing records. */
    fun pill(state: RecorderState, nowMs: Long): String? = when (state) {
        RecorderState.Idle -> null
        is RecorderState.Recording -> "REC " + elapsed(nowMs - state.startedAtMs)
        is RecorderState.Note -> "NOTE " + elapsed(nowMs - state.startedAtMs)
    }

    /** "Nothing waiting", "1 chunk waiting (0.9 MB)", "3 chunks waiting (2.8 MB)". */
    fun queue(status: QueueStatus): String {
        val base = when (status.chunksWaiting) {
            0 -> "Nothing waiting to upload"
            1 -> "1 chunk waiting"
            else -> "${status.chunksWaiting} chunks waiting"
        }
        val size = if (status.bytes > 0 && status.chunksWaiting > 0) String.format(java.util.Locale.ROOT, " (%.1f MB)", status.bytes / 1_048_576.0) else ""
        val rejected = when (status.rejected) {
            0 -> ""
            1 -> "; 1 chunk refused by the hub, kept on the device"
            else -> "; ${status.rejected} chunks refused by the hub, kept on the device"
        }
        return base + size + rejected
    }
}
