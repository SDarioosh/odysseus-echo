package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: the blocking HTTP seam for Home Assistant REST calls (moved here from
// odysseus/HaSetupClient.kt, same names). Callers take a HaHttp so they are unit-tested with a
// fake; UrlConnectionHaHttp is the real one. The body cap is a parameter: 256 KB by default (phone
// setup, weather), larger for the Music page (HaRestApi).

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL

data class HaResponse(val code: Int, val body: String)

fun interface HaHttp {
    /** [contentType] null = no body. Throws on network errors, and HaException when the answer is over the body cap. */
    fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse
}

/** A GET of raw bytes (images through Home Assistant's proxies). */
class HaBytes(val code: Int, val bytes: ByteArray)

fun interface HaBytesHttp {
    /** Throws IOException on network errors and when the body is larger than [maxBytes]. */
    fun get(url: String, bearer: String?, maxBytes: Int): HaBytes
}

/** Plain HttpURLConnection; certificates are checked by the platform as usual. No redirects. */
object UrlConnectionHaHttp : HaHttp, HaBytesHttp {
    const val DEFAULT_MAX_BODY = 256 * 1024

    override fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse =
        call(method, url, bearer, contentType, body, DEFAULT_MAX_BODY)

    /** This client with a body cap of [maxChars] characters instead of 256 KB. */
    fun withMaxBody(maxChars: Int): HaHttp = HaHttp { method, url, bearer, contentType, body ->
        call(method, url, bearer, contentType, body, maxChars)
    }

    private fun call(
        method: String, url: String, bearer: String?, contentType: String?, body: String?, maxChars: Int,
    ): HaResponse {
        val conn = open(method, url, bearer, "application/json")
        try {
            if (body != null && contentType != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { readText(it, maxChars, url) } ?: ""
            return HaResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** [reader]'s text; HaException when it is longer than [maxChars] (cut short it would be invalid JSON). */
    fun readText(reader: Reader, maxChars: Int, url: String): String {
        val out = StringBuilder()
        val buf = CharArray(8192)
        while (true) {
            val n = reader.read(buf, 0, minOf(buf.size, maxChars - out.length + 1))
            if (n < 0) break
            out.appendRange(buf, 0, n)
            if (out.length > maxChars) throw HaException(tooLarge(url, maxChars))
        }
        return out.toString()
    }

    /** The error for an answer over the cap (without the query, which may carry user text). */
    fun tooLarge(url: String, maxChars: Int): String =
        "Home Assistant's answer to ${url.substringBefore('?').substringAfter("://").substringAfter('/', "")
            .let { "/$it" }} is larger than ${maxChars / 1024} KB"

    override fun get(url: String, bearer: String?, maxBytes: Int): HaBytes {
        val conn = open("GET", url, bearer, "*/*")
        try {
            val code = conn.responseCode
            if (code !in 200..299) return HaBytes(code, ByteArray(0))
            return HaBytes(code, conn.inputStream.use { readCapped(it, maxBytes) })
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String, bearer: String?, accept: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", accept)
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        return conn
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw IOException("larger than $maxBytes bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
