package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: sends the queued chunks to Athena through the owner's hub, contract C4
// (docs/odysseus/recording.md). One pass works through the queue in order until it is empty, a
// chunk must wait (RETRY), or the token can't upload (AUTH). The HTTP itself is behind
// [RelayTransport], so the pass is tested with a fake hub.
//
//   POST <hub>/api/codex/athena/chunk                   multipart: audio (WAV), device, session_id,
//                                                       sequence, timestamp, codec=wav, kind=note (notes only)
//   POST <hub>/api/codex/athena/sessions/<id>/end       after the session's last chunk
//   A 404 is tried once more on /api/codex/sound/... (an older hub); if that works, the older
//   name is used from then on (until the app restarts). A chunk answered 404 on both names means
//   the hub has no relay (RELAY_MISSING): the pass stops and no chunk is marked rejected. A 404 on
//   both for an end (session not found) is the end's own refusal, as before.

import timber.log.Timber
import java.util.UUID

/** One answer from the hub: [code] null means no answer (network error). */
data class RelayReply(val code: Int?, val body: String?)

interface RelayTransport {
    /** POSTs [body] to [path] on the hub, with the Echo's token. */
    fun post(path: String, contentType: String, body: ByteArray): RelayReply
}

enum class PassResult {
    /** Nothing left that is ready. */
    DONE,
    /** Something must wait; try again after [UploadRules.retryDelayMs]. */
    RETRY,
    /** 401/403: the token can't upload recordings. */
    AUTH,
    /** 404 on both relay names for a chunk: the hub can't take recordings; try again hourly. */
    RELAY_MISSING,
}

class RecordingUploader(private val queue: ChunkQueue, private val transport: RelayTransport) {

    /** The relay's path prefix; switches to the older name after a 404 that the older name answers. */
    @Volatile var prefix: String = UploadRules.ATHENA_PREFIX
        private set

    /** Uploads everything ready, in order. [onProgress] runs after each item that left the queue. */
    fun runPass(onProgress: () -> Unit = {}): PassResult {
        while (true) {
            val item = queue.next() ?: return PassResult.DONE
            when (val c = send(item)) {
                UploadClass.OK -> {
                    when (item) {
                        is QueueItem.Chunk -> queue.uploaded(item)
                        is QueueItem.End -> queue.ended(item)
                    }
                    onProgress()
                }
                UploadClass.REJECTED -> {
                    when (item) {
                        is QueueItem.Chunk -> {
                            Timber.w("Recorder: hub refused chunk ${item.info.sequence} of ${item.session.sessionId}; kept, skipped")
                            queue.rejected(item)
                        }
                        is QueueItem.End -> {
                            Timber.w("Recorder: hub refused the end of ${item.session.sessionId}")
                            queue.ended(item)
                        }
                    }
                    onProgress()
                }
                UploadClass.RETRY -> return PassResult.RETRY
                UploadClass.AUTH -> return PassResult.AUTH.also { Timber.w("Recorder: upload refused ($c): relink needed") }
                UploadClass.RELAY_MISSING -> return PassResult.RELAY_MISSING.also {
                    Timber.w("Recorder: the hub has no recording relay (404 on both names); chunks kept")
                }
            }
        }
    }

    private fun send(item: QueueItem): UploadClass {
        val (pathOf, contentType, body) = when (item) {
            is QueueItem.Chunk -> {
                val boundary = "odysseus-rec-" + UUID.randomUUID().toString()
                val wav = runCatching { item.wav.readBytes() }.getOrElse { return UploadClass.RETRY }
                Triple(
                    { p: String -> UploadRules.chunkPath(p) },
                    "multipart/form-data; boundary=$boundary",
                    multipart(boundary, chunkFields(item), wav),
                )
            }
            is QueueItem.End -> Triple(
                { p: String -> UploadRules.endPath(p, item.session.sessionId) },
                "application/x-www-form-urlencoded",
                ByteArray(0),
            )
        }
        var reply = transport.post(pathOf(prefix), contentType, body)
        if (reply.code == 404) {
            val other = if (prefix == UploadRules.ATHENA_PREFIX) UploadRules.SOUND_PREFIX else UploadRules.ATHENA_PREFIX
            val second = transport.post(pathOf(other), contentType, body)
            if (second.code != 404) {
                if (second.code != null) {
                    Timber.i("Recorder: the hub answers on $other; using it")
                    prefix = other
                }
                reply = second
            } else if (item is QueueItem.Chunk) {
                return UploadClass.RELAY_MISSING
            }
        }
        return UploadRules.classify(reply.code, reply.body)
    }

    companion object {
        fun chunkFields(item: QueueItem.Chunk): List<Pair<String, String>> = buildList {
            add("device" to item.session.device)
            add("session_id" to item.info.sessionId)
            add("sequence" to item.info.sequence.toString())
            add("timestamp" to item.info.timestamp)
            add("codec" to "wav")
            if (item.info.kind == KIND_NOTE) add("kind" to KIND_NOTE)
        }

        const val KIND_NOTE = "note"
        const val KIND_RECORDING = "recording"

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
            line("Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"")
            line("Content-Type: audio/wav")
            line()
            out.write(wav)
            out.write(crlf)
            line("--$boundary--")
            return out.toByteArray()
        }
    }
}
