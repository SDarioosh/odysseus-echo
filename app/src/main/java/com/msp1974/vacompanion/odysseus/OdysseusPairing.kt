package com.msp1974.vacompanion.odysseus

// Odysseus fork: pair the device's Odysseus link from a phone (Menu → Odysseus → Pair with phone).
//
// The device runs a tiny HTTP server on PORT only while the pairing screen is open, shows a QR
// code for http://<device>:PORT/pair/<code>, and accepts the base URL and voice token from a form
// on the phone. The code is 128 random bits, valid once, for at most TIMEOUT_MS; the server stops
// after a successful pairing or MAX_BAD_ATTEMPTS wrong requests. The QR uses the device's
// Tailscale address when it has one (traffic then travels inside WireGuard), else its Wi-Fi
// address (plain HTTP on the home network: prefer Tailscale).

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import android.util.Base64

object OdysseusPairing {
    const val PORT = 10801
    const val TIMEOUT_MS = 5 * 60_000L
    private const val MAX_BAD_ATTEMPTS = 5
    private const val MAX_BODY = 8 * 1024

    sealed interface State {
        data object Idle : State
        data class Waiting(val url: String, val otherUrl: String?, val expiresAt: Long) : State
        data class Paired(val message: String, val ok: Boolean) : State
        data class Stopped(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var code: String = ""
    @Volatile private var badAttempts = 0

    /** Tailscale (100.64.0.0/10 on a tun interface) first, then Wi-Fi/Ethernet IPv4. */
    private fun addresses(): Pair<String?, String?> {
        var tailscale: String? = null
        var lan: String? = null
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val b = addr.address
                    val first = b[0].toInt() and 0xff
                    val second = b[1].toInt() and 0xff
                    val ip = addr.hostAddress ?: continue
                    if (first == 100 && second in 64..127) tailscale = tailscale ?: ip
                    else if (addr.isSiteLocalAddress && !nif.name.startsWith("tun")) lan = lan ?: ip
                }
            }
        }
        return tailscale to lan
    }

    fun start() {
        stop("restarted")
        val (ts, lan) = addresses()
        val host = ts ?: lan
        if (host == null) {
            _state.value = State.Stopped("No network address: connect Wi-Fi or Tailscale first")
            return
        }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        code = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        badAttempts = 0
        val expires = System.currentTimeMillis() + TIMEOUT_MS
        val socket = try {
            ServerSocket().apply { reuseAddress = true; soTimeout = 1000; bind(InetSocketAddress(PORT)) }
        } catch (e: Exception) {
            _state.value = State.Stopped("Couldn't open port $PORT: ${e.message}")
            return
        }
        server = socket
        val other = if (ts != null && lan != null) "http://$lan:$PORT/pair/$code" else null
        _state.value = State.Waiting("http://$host:$PORT/pair/$code", other, expires)
        scope.launch {
            try {
                while (server === socket && !socket.isClosed) {
                    if (System.currentTimeMillis() > expires) { stop("Pairing timed out"); break }
                    val client = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                    client.use { handle(it) }
                }
            } catch (e: Exception) {
                if (server === socket) stop("Pairing stopped: ${e.message}")
            }
        }
    }

    fun stop(reason: String = "Pairing closed") {
        val s = server
        server = null
        code = ""
        runCatching { s?.close() }
        if (_state.value is State.Waiting) _state.value = State.Stopped(reason)
    }

    fun reset() { if (server == null) _state.value = State.Idle }

    /** Show the other address (Wi-Fi <-> Tailscale) in the QR code; the same code works on both. */
    fun swapAddress() {
        val st = _state.value as? State.Waiting ?: return
        val other = st.otherUrl ?: return
        _state.value = st.copy(url = other, otherUrl = st.url)
    }

    // --- HTTP ------------------------------------------------------------------------------

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val out = socket.getOutputStream()

        val expected = "/pair/$code"
        if (code.isEmpty() || path != expected) {
            if (path != "/favicon.ico") badAttempt()
            return respond(out, 404, page("Not found", "<p>This pairing link is not valid any more. Open Menu → Odysseus → Pair with phone on the device for a new one.</p>"))
        }
        when (method) {
            "GET" -> respond(out, 200, formPage())
            "POST" -> {
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len !in 1..MAX_BODY) return respond(out, 400, page("Error", "<p>Request too large or empty.</p>"))
                val body = ByteArray(len)
                var read = 0
                while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
                val form = parseForm(String(body, 0, read))
                val url = form["base_url"].orEmpty().trim()
                val token = form["token"].orEmpty().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://") || token.isEmpty()) {
                    return respond(out, 200, formPage("Enter the base URL (starting with http:// or https://) and the token."))
                }
                OdysseusLink.baseUrl = url
                OdysseusLink.setToken(token)
                VoiceTurns.resetFeed()
                val (ok, msg) = runBlocking { OdysseusLink.testLink() }
                Timber.i("Odysseus pairing: saved from phone (${msg})")
                respond(out, 200, page("Saved", "<p><b>${esc(msg)}</b></p><p>You can close this page.</p>"))
                _state.value = State.Paired(msg, ok)
                val s = server; server = null; code = ""; runCatching { s?.close() }
            }
            else -> respond(out, 405, page("Error", "<p>Method not allowed.</p>"))
        }
    }

    private fun badAttempt() {
        badAttempts++
        if (badAttempts >= MAX_BAD_ATTEMPTS) stop("Pairing stopped after $MAX_BAD_ATTEMPTS wrong requests")
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private fun parseForm(body: String): Map<String, String> = body.split("&").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else runCatching {
            URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.getOrNull()
    }.toMap()

    private fun respond(out: OutputStream, status: Int, html: String) {
        val bytes = html.toByteArray()
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed")[status] ?: "OK"
        out.write(("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Frame-Options: DENY\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun formPage(error: String? = null): String = page("Link this Echo to Odysseus", """
        <p>Paste the base URL and the voice token from Odysseus → Settings → Integrations → Voice assistant
        (the same ones Home Assistant uses).</p>
        ${if (error != null) "<p class=err>${esc(error)}</p>" else ""}
        <form method=post>
          <label>Base URL<input name=base_url type=url required placeholder="https://odysseus.example.ts.net/api/chat/voice/v1"
            value="${esc(OdysseusLink.baseUrl)}" autocapitalize=off autocorrect=off></label>
          <label>Voice token<input name=token type=password required autocomplete=off autocapitalize=off></label>
          <button type=submit>Save</button>
        </form>""")

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
        <style>body{font-family:system-ui,sans-serif;margin:24px;max-width:520px;color:#222}
        label{display:block;margin:16px 0;font-weight:600}input{display:block;width:100%;box-sizing:border-box;
        font-size:17px;padding:10px;margin-top:6px;border:1px solid #bbb;border-radius:8px}
        button{font-size:18px;padding:12px 24px;border:0;border-radius:8px;background:#1565c0;color:#fff}
        .err{color:#b71c1c}</style></head><body><h2>${esc(title)}</h2>$body</body></html>"""
}
