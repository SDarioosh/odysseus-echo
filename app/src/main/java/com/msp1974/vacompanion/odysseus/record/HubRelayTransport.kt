package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's HTTP to the owner's hub. The hub is the one the Echo is linked to
// (OdysseusLink: its voice base URL and token); the relay is on the same origin under
// /api/codex/athena (UploadRules.hubOrigin). Audio goes nowhere else.

import com.msp1974.vacompanion.odysseus.OdysseusLink
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HubRelayTransport : RelayTransport {
    override fun post(path: String, contentType: String, body: ByteArray): RelayReply {
        val origin = UploadRules.hubOrigin(OdysseusLink.baseUrl) ?: return RelayReply(null, null)
        val token = OdysseusLink.bearerToken()
        if (token.isNullOrEmpty()) return RelayReply(null, null)
        return postTo(origin + path, token, contentType, body)
    }

    /** Changes when the Echo is relinked (a new token), so uploads stopped by a 401/403 start again. */
    fun tokenFingerprint(): Int = OdysseusLink.bearerToken()?.hashCode() ?: 0

    companion object {
        /** The most of an answer's body that is read (the relay answers a few bytes of JSON). */
        const val MAX_BODY_BYTES = 16 * 1024

        /**
         * One POST with the token. Redirects are not followed (the token would go to wherever a
         * redirect points): a 3xx comes back as its code, which UploadRules classes as "try again
         * later". At most [MAX_BODY_BYTES] of a 2xx body are read. Never throws.
         */
        fun postTo(url: String, token: String, contentType: String, body: ByteArray): RelayReply = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val text = if (code in 200..299) conn.inputStream.use { readCapped(it) } else null
                RelayReply(code, text)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            RelayReply(null, null)
        }

        /** Up to [max] bytes of [input] as UTF-8 text; the rest is left unread. */
        fun readCapped(input: InputStream, max: Int = MAX_BODY_BYTES): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (out.size() < max) {
                val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
