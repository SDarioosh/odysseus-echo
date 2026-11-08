package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's HTTP to the owner's hub. The hub is the one the Echo is linked to
// (OdysseusLink: its voice base URL and token); the relay is on the same origin under
// /api/codex/athena (UploadRules.hubOrigin). Audio goes nowhere else.

import com.msp1974.vacompanion.odysseus.OdysseusLink

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
        const val MAX_BODY_CHARS = 16 * 1024

        /**
         * One POST with the token (OdysseusLink.send). Redirects are not followed: a 3xx comes back
         * as its code, which UploadRules classes as "try again later". At most [MAX_BODY_CHARS] of
         * a 2xx body are read; other answers have no body. Never throws.
         */
        fun postTo(url: String, token: String, contentType: String, body: ByteArray): RelayReply = try {
            val r = OdysseusLink.send(
                url, token, contentType, body,
                readTimeoutMs = 60_000, connectTimeoutMs = 10_000, maxBodyChars = MAX_BODY_CHARS,
            )
            RelayReply(r.code, r.body.takeIf { r.code in 200..299 })
        } catch (e: Exception) {
            RelayReply(null, null)
        }
    }
}
