package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork: wake word clips waiting to go to the hub (docs/odysseus/wake-word-training.md).
// Plain Kotlin over java.io.File, unit-tested with a temporary folder (WakeClipQueueTest).
//
//   <root>/<recorded ms>-<clip id>.json   the clip's fields (written first)
//   <root>/<recorded ms>-<clip id>.wav    the clip (renamed into place last: "ready")
//
// At most [maxClips] wait; the oldest go first when it is full. A clip leaves the queue once the
// hub has it, or when the hub refuses it for good (a 4xx other than 401/403/404/408/429).
//
// Upload: POST <voice base>/satellite/wakeword/clip, multipart: audio (WAV), clip_id, device_id,
// device_name, wake_word, kind, score, threshold, prompt, recorded_at. Answer {"ok": true, ...};
// a clip already there is {"ok": true, "already": true}. 404/405: an older hub without the route.

import com.msp1974.vacompanion.odysseus.Wav
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ClipInfo(
    @SerialName("clip_id") val clipId: String,
    @SerialName("wake_word") val wakeWord: String,
    val kind: String,
    val score: Float,
    val threshold: Float,
    val prompt: String = "",
    @SerialName("recorded_at") val recordedAt: Long,
)

/** One answer from the hub: [code] null means no answer (network error). */
data class ClipReply(val code: Int?, val body: String?)

fun interface ClipTransport {
    fun post(contentType: String, body: ByteArray): ClipReply
}

enum class ClipPass {
    /** Nothing left. */
    DONE,
    /** Try again later. */
    RETRY,
    /** 401/403: relink the Echo. */
    AUTH,
    /** 404/405: the hub has no clip route yet; clips are kept. */
    NO_ROUTE,
    /** The Echo isn't linked to Odysseus; clips are kept. */
    NOT_LINKED,
}

class WakeClipQueue(private val root: File, private val maxClips: Int = MAX_CLIPS) {
    private val json = Json { ignoreUnknownKeys = true }

    init { root.mkdirs() }

    /** Keeps [clip] for upload, dropping the oldest if the queue is full. Returns its id. */
    @Synchronized
    fun add(clip: WakeClip, wakeWord: String): String {
        val id = UUID.randomUUID().toString()
        val info = ClipInfo(id, wakeWord, clip.kind.wire, clip.score, clip.threshold, clip.prompt, clip.recordedAt)
        val name = "${clip.recordedAt}-$id"
        writeAtomic(File(root, "$name.json"), json.encodeToString(ClipInfo.serializer(), info).toByteArray())
        writeAtomic(File(root, "$name.wav"), Wav.encodePcm16Mono(if (clip.pcm.size > MAX_SAMPLES) clip.pcm.copyOf(MAX_SAMPLES) else clip.pcm, SAMPLE_RATE))
        val ready = readyNames()
        if (ready.size > maxClips) ready.take(ready.size - maxClips).forEach { remove(it) }
        return id
    }

    /** Clips waiting. */
    @Synchronized
    fun size(): Int = readyNames().size

    /** Sends every waiting clip, oldest first, until one must wait. [onSent] after each that left. */
    fun upload(deviceId: String, deviceName: String, transport: ClipTransport, onSent: () -> Unit = {}): ClipPass {
        while (true) {
            val name = synchronized(this) { readyNames().firstOrNull() } ?: return ClipPass.DONE
            val info = runCatching {
                json.decodeFromString(ClipInfo.serializer(), File(root, "$name.json").readText())
            }.getOrNull()
            val wav = runCatching { File(root, "$name.wav").readBytes() }.getOrNull()
            if (info == null || wav == null) { synchronized(this) { remove(name) }; continue }
            val boundary = "odysseus-ww-" + UUID.randomUUID().toString()
            val reply = transport.post(
                "multipart/form-data; boundary=$boundary",
                multipart(boundary, fields(info, deviceId, deviceName), wav),
            )
            when (classify(reply)) {
                ClipOutcome.SENT, ClipOutcome.REFUSED -> { synchronized(this) { remove(name) }; onSent() }
                ClipOutcome.RETRY -> return ClipPass.RETRY
                ClipOutcome.AUTH -> return ClipPass.AUTH
                ClipOutcome.NO_ROUTE -> return ClipPass.NO_ROUTE
            }
        }
    }

    private fun readyNames(): List<String> =
        (root.listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray())
            .map { it.name.removeSuffix(".wav") }
            .sortedBy { it.substringBefore('-').toLongOrNull() ?: 0L }

    private fun remove(name: String) {
        File(root, "$name.wav").delete()
        File(root, "$name.json").delete()
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    enum class ClipOutcome { SENT, REFUSED, RETRY, AUTH, NO_ROUTE }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_CLIPS = 200
        const val PATH = "/satellite/wakeword/clip"

        private val OK_TRUE = Regex("\"ok\"\\s*:\\s*true")

        fun classify(reply: ClipReply): ClipOutcome {
            val code = reply.code ?: return ClipOutcome.RETRY
            return when {
                code in 200..299 -> if (reply.body != null && OK_TRUE.containsMatchIn(reply.body)) ClipOutcome.SENT else ClipOutcome.RETRY
                code == 401 || code == 403 -> ClipOutcome.AUTH
                code == 404 || code == 405 -> ClipOutcome.NO_ROUTE
                code == 408 || code == 429 || code == 507 -> ClipOutcome.RETRY
                code in 400..499 -> ClipOutcome.REFUSED
                else -> ClipOutcome.RETRY
            }
        }

        fun fields(info: ClipInfo, deviceId: String, deviceName: String): List<Pair<String, String>> = buildList {
            add("clip_id" to info.clipId)
            add("device_id" to deviceId)
            if (deviceName.isNotBlank()) add("device_name" to deviceName.take(100))
            add("wake_word" to info.wakeWord)
            add("kind" to info.kind)
            add("score" to "%.3f".format(java.util.Locale.ROOT, info.score))
            add("threshold" to "%.3f".format(java.util.Locale.ROOT, info.threshold))
            if (info.prompt.isNotBlank()) add("prompt" to info.prompt.take(100))
            add("recorded_at" to info.recordedAt.toString())
        }

        fun multipart(boundary: String, fields: List<Pair<String, String>>, wav: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(wav.size + 1024)
            val crlf = "\r\n".toByteArray(Charsets.US_ASCII)
            fun line(s: String = "") { out.write(s.toByteArray(Charsets.UTF_8)); out.write(crlf) }
            for ((name, value) in fields) {
                line("--$boundary")
                line("Content-Disposition: form-data; name=\"$name\"")
                line()
                line(value)
            }
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"audio\"; filename=\"clip.wav\"")
            line("Content-Type: audio/wav")
            line()
            out.write(wav)
            out.write(crlf)
            line("--$boundary--")
            return out.toByteArray()
        }

        /** The hub takes at most 10 s; clips are shorter, this only guards against a bug. */
        const val MAX_SAMPLES = SAMPLE_RATE * 10
    }
}
