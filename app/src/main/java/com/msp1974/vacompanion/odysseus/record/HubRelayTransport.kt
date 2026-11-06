package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder's HTTP to the owner's hub. The hub is the one the Echo is linked to
// (OdysseusLink: its voice base URL and token); the relay is on the same origin under
// /api/codex/athena (UploadRules.hubOrigin). Audio goes nowhere else.

import com.msp1974.vacompanion.odysseus.OdysseusLink
import java.net.HttpURLConnection
import java.net.URL

class HubRelayTransport : RelayTransport {
    override fun post(path: String, contentType: String, body: ByteArray): RelayReply {
        val origin = UploadRules.hubOrigin(OdysseusLink.baseUrl) ?: return RelayReply(null, null)
        val token = OdysseusLink.bearerToken()
        if (token.isNullOrEmpty()) return RelayReply(null, null)
        return try {
            val conn = URL(origin + path).openConnection() as HttpURLConnection
            try {
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
                val text = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
                RelayReply(code, text)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            RelayReply(null, null)
        }
    }

    /** Changes when the Echo is relinked (a new token), so uploads stopped by a 401/403 start again. */
    fun tokenFingerprint(): Int = OdysseusLink.bearerToken()?.hashCode() ?: 0
}
