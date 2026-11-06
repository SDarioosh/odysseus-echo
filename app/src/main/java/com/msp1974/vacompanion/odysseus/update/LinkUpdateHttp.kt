package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: UpdateHttp over the device's Odysseus link (base URL and voice token from
// OdysseusLink). The connection stays open while the APK streams; UpdateResponse.close ends it.

import com.msp1974.vacompanion.odysseus.OdysseusLink

object LinkUpdateHttp : UpdateHttp {
    override fun getText(path: String): UpdateResponse {
        val conn = OdysseusLink.openGet(path, readTimeoutMs = 15_000)
        try {
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText().take(64 * 1024) } ?: ""
            return UpdateResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }

    override fun getStream(path: String, rangeFrom: Long): UpdateResponse {
        val conn = OdysseusLink.openGet(path, readTimeoutMs = 60_000)
        try {
            if (rangeFrom > 0) conn.setRequestProperty("Range", "bytes=$rangeFrom-")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return UpdateResponse(code)
            }
            return UpdateResponse(code, contentLength = conn.contentLengthLong, body = conn.inputStream) {
                conn.disconnect()
            }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }
}
