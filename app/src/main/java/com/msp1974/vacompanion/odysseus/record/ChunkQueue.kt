package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's durable upload queue (docs/odysseus/recording.md). Every chunk is
// written to app-private storage before it is uploaded, so recordings survive a restart and a
// missing network. Plain Kotlin over java.io.File, unit-tested with a temporary folder.
//
//   <root>/<created ms>-<session id>/
//       session.json          session id, kind, device, created time
//       000000.json           sidecar: session id, sequence, kind, timestamp (written first)
//       000000.wav            the chunk (renamed into place last: its presence means "ready")
//       000004.wav.rejected   the hub refused it: kept, skipped
//       end                   the session is finished; its text is the number of chunks
//       ended                 `/end` was delivered, only rejected chunks are left
//       sent                  the highest sequence delivered (its files are gone), for finishAbandoned
//
// Sessions are worked through oldest first, chunks by sequence. Files are written to a .tmp name
// and renamed, so a crash never leaves half a chunk that looks ready.

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SessionInfo(
    @SerialName("session_id") val sessionId: String,
    /** "note" or "recording". */
    val kind: String,
    val device: String,
    @SerialName("created_ms") val createdMs: Long,
)

@Serializable
data class ChunkInfo(
    @SerialName("session_id") val sessionId: String,
    val sequence: Int,
    val kind: String,
    /** Chunk start, ISO-8601 UTC with ms. */
    val timestamp: String,
)

sealed interface QueueItem {
    val dir: File
    val session: SessionInfo

    data class Chunk(override val dir: File, override val session: SessionInfo, val info: ChunkInfo, val wav: File) : QueueItem
    data class End(override val dir: File, override val session: SessionInfo, val chunks: Int) : QueueItem
}

data class QueueStatus(val chunksWaiting: Int, val bytes: Long, val sessions: Int, val rejected: Int)

class ChunkQueue(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        root.mkdirs()
    }

    /** Starts a session folder; returns its folder name, used as the session's handle here. */
    @Synchronized
    fun createSession(info: SessionInfo): File {
        val dir = File(root, "${info.createdMs}-${info.sessionId}")
        dir.mkdirs()
        writeAtomic(File(dir, SESSION_FILE), json.encodeToString(SessionInfo.serializer(), info).toByteArray())
        return dir
    }

    /** Writes one chunk: sidecar first, then the WAV, which makes it ready to upload. */
    fun writeChunk(dir: File, info: ChunkInfo, wav: ByteArray) {
        val name = seqName(info.sequence)
        writeAtomic(File(dir, "$name.json"), json.encodeToString(ChunkInfo.serializer(), info).toByteArray())
        writeAtomic(File(dir, "$name.wav"), wav)
    }

    /** No more chunks will come: after the last one is uploaded, `/end` is sent. */
    fun finishSession(dir: File, chunks: Int) {
        writeAtomic(File(dir, END_FILE), chunks.toString().toByteArray())
    }

    /**
     * Marks every unfinished session not in [activeDirs] as finished: left over from a run that
     * stopped (the app was killed while recording). Its chunk count is the highest sequence + 1.
     */
    @Synchronized
    fun finishAbandoned(activeDirs: Set<File> = emptySet()): Int {
        var n = 0
        for (dir in sessionDirs()) {
            if (dir in activeDirs || File(dir, END_FILE).exists()) continue
            val written = dir.listFiles().orEmpty()
                .mapNotNull { f -> f.name.takeIf { it.endsWith(".json") && it != SESSION_FILE }?.substringBefore('.')?.toIntOrNull() }
                .maxOrNull()
            // Delivered chunks' files are gone: without "sent", a session whose chunks were all
            // uploaded would count 0 and be deleted without its `/end`.
            val sent = runCatching { File(dir, SENT_FILE).readText().trim().toInt() }.getOrNull()
            val highest = listOfNotNull(written, sent).maxOrNull()
            finishSession(dir, (highest ?: -1) + 1)
            n++
        }
        return n
    }

    /** The next thing to upload, in order, or null when nothing is ready. */
    @Synchronized
    fun next(): QueueItem? {
        for (dir in sessionDirs()) {
            val session = readSession(dir)
            if (session == null) {
                // No session.json: a folder from a crash at the very start. Nothing to send.
                if (dir.listFiles().orEmpty().none { it.name.endsWith(".wav") }) dir.deleteRecursively()
                continue
            }
            // Read "end" before listing chunks: once it exists every chunk has been written.
            val end = File(dir, END_FILE)
            val finished = end.exists()
            val chunk = pendingChunks(dir).firstOrNull()
            if (chunk != null) {
                val info = readChunk(chunk) ?: continue
                return QueueItem.Chunk(dir, session, info, chunk)
            }
            if (!finished || File(dir, ENDED_FILE).exists()) continue
            val count = end.readText().trim().toIntOrNull() ?: 0
            if (count == 0) {
                // Nothing was recorded (stopped at once, or no audio): there is no session to end.
                dir.deleteRecursively()
                continue
            }
            return QueueItem.End(dir, session, count)
        }
        return null
    }

    /** The chunk was delivered: delete it and its sidecar. */
    fun uploaded(item: QueueItem.Chunk) {
        runCatching { writeAtomic(File(item.dir, SENT_FILE), item.info.sequence.toString().toByteArray()) }
        item.wav.delete()
        File(item.dir, seqName(item.info.sequence) + ".json").delete()
    }

    /** The hub refused the chunk: keep the audio under another name and skip it. */
    fun rejected(item: QueueItem.Chunk) {
        item.wav.renameTo(File(item.dir, item.wav.name + REJECTED_SUFFIX))
    }

    /** `/end` was delivered (or refused): the session is done. Kept only for its rejected chunks. */
    @Synchronized
    fun ended(item: QueueItem.End) {
        val keep = item.dir.listFiles().orEmpty().any { it.name.endsWith(REJECTED_SUFFIX) }
        if (keep) writeAtomic(File(item.dir, ENDED_FILE), ByteArray(0)) else item.dir.deleteRecursively()
    }

    @Synchronized
    fun status(): QueueStatus {
        var chunks = 0
        var bytes = 0L
        var rejected = 0
        val dirs = sessionDirs()
        for (dir in dirs) {
            for (f in dir.listFiles().orEmpty()) {
                bytes += f.length()
                if (f.name.endsWith(".wav")) chunks++
                if (f.name.endsWith(REJECTED_SUFFIX)) rejected++
            }
        }
        return QueueStatus(chunks, bytes, dirs.size, rejected)
    }

    /** Deletes the oldest finished sessions while the queue is over [maxBytes]; returns how many. */
    @Synchronized
    fun enforceCap(maxBytes: Long = QueueCap.MAX_BYTES): Int {
        val dirs = sessionDirs()
        val sessions = dirs.map { dir ->
            QueuedSession(
                id = dir.name,
                createdMs = dir.name.substringBefore('-').toLongOrNull() ?: 0L,
                bytes = dir.listFiles().orEmpty().sumOf { it.length() },
                finished = File(dir, END_FILE).exists(),
            )
        }
        val drop = QueueCap.sessionsToDrop(sessions, maxBytes)
        for (name in drop) File(root, name).deleteRecursively()
        return drop.size
    }

    /** Deletes everything (used by tests and "Clear queue"). */
    @Synchronized
    fun clear() {
        sessionDirs().forEach { it.deleteRecursively() }
    }

    private fun sessionDirs(): List<File> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .sortedWith(compareBy({ it.name.substringBefore('-').toLongOrNull() ?: 0L }, { it.name }))

    private fun pendingChunks(dir: File): List<File> = dir.listFiles().orEmpty()
        .filter { it.name.endsWith(".wav") && File(dir, it.name.removeSuffix(".wav") + ".json").exists() }
        .sortedBy { it.name }

    private fun readSession(dir: File): SessionInfo? = runCatching {
        json.decodeFromString(SessionInfo.serializer(), File(dir, SESSION_FILE).readText())
    }.getOrNull()

    private fun readChunk(wav: File): ChunkInfo? = runCatching {
        json.decodeFromString(ChunkInfo.serializer(), File(wav.parentFile, wav.name.removeSuffix(".wav") + ".json").readText())
    }.getOrNull()

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)

        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Could not write ${target.name}")
        }
    }

    companion object {
        const val SESSION_FILE = "session.json"
        const val END_FILE = "end"
        const val ENDED_FILE = "ended"
        const val SENT_FILE = "sent"
        const val REJECTED_SUFFIX = ".rejected"

        fun seqName(sequence: Int): String = "%06d".format(sequence)
    }
}
