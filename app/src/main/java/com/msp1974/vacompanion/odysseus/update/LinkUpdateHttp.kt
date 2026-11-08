package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: UpdateHttp over the device's Odysseus link (base URL and voice token from
// OdysseusLink). The release is a plain hub request (OdysseusLink.sendToHub); the APK streams over
// OdysseusLink.openGet, whose connection stays open until UpdateResponse.close ends it.

import com.msp1974.vacompanion.odysseus.OdysseusLink

object LinkUpdateHttp : UpdateHttp {
    override fun getText(path: String): UpdateResponse =
        OdysseusLink.sendToHub(path, readTimeoutMs = 15_000, connectTimeoutMs = 10_000, maxBodyChars = 64 * 1024)
            .let { UpdateResponse(it.code, it.body) }

    override fun getStream(path: String, rangeFrom: Long): UpdateResponse {
        val conn = OdysseusLink.openGet(path, readTimeoutMs = 60_000)
        try {
            if (rangeFrom > 0) conn.setRequestProperty("Range", "bytes=$rangeFrom-")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return UpdateResponse(code)
            }
            return UpdateResponse(
                code, contentLength = conn.contentLengthLong, body = conn.inputStream,
                contentRange = conn.getHeaderField("Content-Range"),
            ) {
                conn.disconnect()
            }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }
}
