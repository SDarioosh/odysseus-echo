package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork: wake word clips waiting to go to the hub (docs/odysseus/wake-word-training.md).
// Plain Kotlin over java.io.File, unit-tested with a temporary folder and a fake hub
// (WakeClipQueueTest).
//
//   <root>/<recorded ms>-<clip id>.json   the clip's fields (written first)
//   <root>/<recorded ms>-<clip id>.wav    the clip (renamed into place last: "ready")
//
// At most [maxClips] wait; the oldest go first when it is full. A clip leaves the queue once the
// hub has it, or when the hub refuses it for good (a 4xx other than 401/403/404/405/408/429).
//
// Upload, through the HubHttp seam (OdysseusLink on the device): POST <voice base>/satellite/wakeword/clip,
// multipart: audio (WAV), clip_id, device_id, device_name, wake_word, kind, score, threshold,
// prompt, recorded_at. Answer {"ok": true, "id", "already"}.

import com.msp1974.vacompanion.odysseus.HubCall
import com.msp1974.vacompanion.odysseus.HubHttp
import com.msp1974.vacompanion.odysseus.HubJson
import com.msp1974.vacompanion.odysseus.HubReply
import com.msp1974.vacompanion.odysseus.VoiceTurnApi
import com.msp1974.vacompanion.odysseus.Wav
import com.msp1974.vacompanion.odysseus.hubStatusText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

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

/** The hub's answer to one clip. */
@Serializable
data class ClipAnswer(val ok: Boolean = false, val already: Boolean = false)

enum class ClipPass {
    /** Nothing left. */
    DONE,
    /** Try again later. */
    RETRY,
    /** 401/403: relink the Echo. */
    AUTH,
}

/** One upload pass: how it ended and, for a failure, the answer or error ([hubStatusText]). */
data class UploadResult(val pass: ClipPass, val detail: String? = null)

class WakeClipQueue(private val root: File, private val maxClips: Int = MAX_CLIPS) {

    init { root.mkdirs() }

    /** Keeps [clip] for upload, dropping the oldest if the queue is full. Returns its id. */
    @Synchronized
    fun add(clip: WakeClip, wakeWord: String): String {
        val id = UUID.randomUUID().toString()
        val info = ClipInfo(id, wakeWord, clip.kind.wire, clip.score, clip.threshold, clip.prompt, clip.recordedAt)
        val name = "${clip.recordedAt}-$id"
        writeAtomic(File(root, "$name.json"), HubJson.encodeToString(ClipInfo.serializer(), info).toByteArray())
        writeAtomic(File(root, "$name.wav"), Wav.encodePcm16Mono(if (clip.pcm.size > MAX_SAMPLES) clip.pcm.copyOf(MAX_SAMPLES) else clip.pcm, SAMPLE_RATE))
        val ready = readyNames()
        if (ready.size > maxClips) ready.take(ready.size - maxClips).forEach { remove(it) }
        return id
    }

    /** Clips waiting. */
    @Synchronized
    fun size(): Int = readyNames().size

    /** Sends every waiting clip, oldest first, until one must wait. [onSent] after each that left. */
    suspend fun upload(deviceId: String, deviceName: String, hub: HubHttp, onSent: () -> Unit = {}): UploadResult {
        while (true) {
            val name = synchronized(this) { readyNames().firstOrNull() } ?: return UploadResult(ClipPass.DONE)
            val info = runCatching {
                HubJson.decodeFromString(ClipInfo.serializer(), File(root, "$name.json").readText())
            }.getOrNull()
            val wav = runCatching { File(root, "$name.wav").readBytes() }.getOrNull()
            if (info == null || wav == null) { synchronized(this) { remove(name) }; continue }
            val boundary = "odysseus-ww-" + UUID.randomUUID().toString()
            val body = VoiceTurnApi.multipartBody(boundary, fields(info, deviceId, deviceName), wav, audioFileName = "clip.wav")
            val outcome = try {
                classify(hub.request(HubCall(PATH, "multipart/form-data; boundary=$boundary", body, readTimeoutMs = 15_000)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return UploadResult(ClipPass.RETRY, e.message ?: e.javaClass.simpleName)
            }
            when (outcome) {
                ClipOutcome.Sent, ClipOutcome.Refused -> { synchronized(this) { remove(name) }; onSent() }
                is ClipOutcome.Retry -> return UploadResult(ClipPass.RETRY, outcome.detail)
                is ClipOutcome.Auth -> return UploadResult(ClipPass.AUTH, outcome.detail)
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

    sealed interface ClipOutcome {
        /** The hub has it (also when it already had it). */
        data object Sent : ClipOutcome
        /** The hub won't ever take it: dropped. */
        data object Refused : ClipOutcome
        data class Retry(val detail: String) : ClipOutcome
        data class Auth(val detail: String) : ClipOutcome
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_CLIPS = 200
        const val PATH = "/satellite/wakeword/clip"

        /** The hub takes at most 10 s; clips are shorter, this only guards against a bug. */
        const val MAX_SAMPLES = SAMPLE_RATE * 10

        fun classify(reply: HubReply): ClipOutcome {
            val code = reply.code
            return when {
                code in 200..299 -> {
                    val ok = runCatching { HubJson.decodeFromString(ClipAnswer.serializer(), reply.body).ok }.getOrDefault(false)
                    if (ok) ClipOutcome.Sent else ClipOutcome.Retry("unreadable answer")
                }
                code == 401 || code == 403 -> ClipOutcome.Auth(hubStatusText(code))
                // 404/405 (no such route: a wrong base URL), 408, 429, 507 (the hub's store is full):
                // the clip is kept and sent again later.
                code == 404 || code == 405 || code == 408 || code == 429 || code == 507 -> ClipOutcome.Retry(hubStatusText(code))
                code in 400..499 -> ClipOutcome.Refused
                else -> ClipOutcome.Retry(hubStatusText(code))
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
    }
}
