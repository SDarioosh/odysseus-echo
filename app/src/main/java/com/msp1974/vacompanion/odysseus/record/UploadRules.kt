package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's upload rules, contract C4 (docs/odysseus/recording.md). Plain
// Kotlin, unit-tested: how an answer from the hub is classed, when to try again, where the relay
// is, and which old sessions go when the queue is full.

import java.security.SecureRandom

enum class UploadClass {
    /** Delivered (a duplicate counts): delete the chunk. */
    OK,
    /** Try again later, in order. */
    RETRY,
    /** The token can't upload: stop until the Echo is relinked. */
    AUTH,
    /** The hub refused this chunk: keep the file, skip it, log it. */
    REJECTED,
    /**
     * The hub has no recording relay (404 on both /api/codex/athena and /api/codex/sound): a hub
     * problem, not this chunk's. Stop uploads, keep every chunk, try again hourly.
     */
    RELAY_MISSING,
}

object UploadRules {
    private val OK_TRUE = Regex("\"ok\"\\s*:\\s*true")

    /**
     * Classes one answer. [code] is null for a network error (no answer at all); [body] is the
     * answer's text for a 2xx.
     */
    fun classify(code: Int?, body: String?): UploadClass = when {
        code == null -> UploadClass.RETRY
        code in 200..299 -> if (body != null && OK_TRUE.containsMatchIn(body)) UploadClass.OK else UploadClass.RETRY
        code == 401 || code == 403 -> UploadClass.AUTH
        code == 408 || code == 429 -> UploadClass.RETRY
        code in 400..499 -> UploadClass.REJECTED
        else -> UploadClass.RETRY   // 5xx, and anything odd (1xx, 3xx)
    }

    /** How long uploads wait after [UploadClass.RELAY_MISSING] ("Upload now" tries at once). */
    const val RELAY_MISSING_RETRY_MS = 60 * 60_000L
    const val RELAY_MISSING_MESSAGE = "Odysseus can't take recordings (relay missing)"

    /** Waits after the 1st, 2nd, … failure in a row: 1, 2, 5, 10, then every 15 minutes. */
    private val RETRY_MINUTES = longArrayOf(1, 2, 5, 10, 15)

    fun retryDelayMs(failuresInARow: Int): Long {
        val i = (failuresInARow - 1).coerceIn(0, RETRY_MINUTES.size - 1)
        return RETRY_MINUTES[i] * 60_000L
    }

    /**
     * The hub's origin (scheme://host[:port]) from the voice base URL the app stores,
     * `https://hub.example:7000/api/chat/voice/v1` -> `https://hub.example:7000`. Null when unset.
     */
    fun hubOrigin(voiceBaseUrl: String): String? {
        val base = voiceBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val afterScheme = base.indexOf("://") + 3
        val slash = base.indexOf('/', afterScheme)
        val origin = if (slash < 0) base else base.substring(0, slash)
        // A hub served under a path prefix keeps it: everything before "/api/".
        val api = base.indexOf("/api/", afterScheme)
        return if (api > 0) base.substring(0, api) else origin
    }

    const val ATHENA_PREFIX = "/api/codex/athena"
    /** An older hub's name for the same relay; tried once after a 404. */
    const val SOUND_PREFIX = "/api/codex/sound"

    fun chunkPath(prefix: String) = "$prefix/chunk"
    fun endPath(prefix: String, sessionId: String) = "$prefix/sessions/$sessionId/end"

    /** 16 random lowercase hex digits. */
    fun newSessionId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(8).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The `device` field: `echo-<device id>`. */
    fun deviceField(deviceId: String): String = "echo-" + deviceId.trim().ifEmpty { "unknown" }
}

/** What the queue knows about one session on disk, for [QueueCap]. */
data class QueuedSession(val id: String, val createdMs: Long, val bytes: Long, val finished: Boolean)

object QueueCap {
    const val MAX_BYTES = 500L * 1024 * 1024

    /**
     * The sessions to delete so the queue fits in [maxBytes]: the oldest *finished* ones first.
     * A session still recording is never dropped, so the queue may stay over the cap while it runs.
     */
    fun sessionsToDrop(sessions: List<QueuedSession>, maxBytes: Long = MAX_BYTES): List<String> {
        var total = sessions.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val drop = mutableListOf<String>()
        for (s in sessions.filter { it.finished }.sortedBy { it.createdMs }) {
            if (total <= maxBytes) break
            drop += s.id
            total -= s.bytes
        }
        return drop
    }
}
