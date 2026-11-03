package com.msp1974.vacompanion.odysseus

// Odysseus fork: set up a new Echo from a phone with one QR code (docs/odysseus/setup.md).
//
// While the setup screen is open (the connection screen of an Echo with no Home Assistant login,
// or Menu → Odysseus → Set up with phone), the Echo serves http://<echo-host>:10801/setup/<code>:
//   GET  /setup/<code>              name + Odysseus address form, or progress
//   POST /setup/<code>              -> 303 <odysseus>/echo/link?return=…/setup/<code>/odysseus&name=…
//   POST /setup/<code>/odysseus     token, base_url, ha_url from Odysseus -> 303 HA sign-in
//   POST /setup/<code>/ha-url       ha_url when Odysseus sent none
//   GET  /setup/<code>/ha           HA OAuth callback (state checked) -> tokens, then the HA job
//   GET  /setup/<code>/status       progress as JSON
// The HA job adds the Echo to Home Assistant (VACA entry, Wyoming port 10800, option ha_url) and
// sets wake word Hey Athena, threshold 9.2 and wake sound Ding (HaSetupClient). A failed step
// doesn't undo the ones before it, and running setup again finds what's already there.
//
// Security: the code is 128 random bits and the only guard (Odysseus posts cross-origin by
// design); a wrong code is a 404 and the 5th one stops the server. The server stops after 10
// minutes, when the screen is left (unless the HA job is running), and a minute after the job
// ends. Pages are no-store and no-referrer. Tokens are never logged.

import com.msp1974.vacompanion.BuildConfig
import com.msp1974.vacompanion.odysseus.EchoSetupRules.esc
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object EchoSetup {
    const val PORT = EchoSetupRules.SETUP_PORT
    const val TIMEOUT_MS = 10 * 60_000L
    private const val GRACE_MS = 60_000L
    private const val MAX_BAD_ATTEMPTS = 5
    private const val MAX_BODY = 16 * 1024

    enum class StepStatus { PENDING, RUNNING, OK, WARN, FAILED, SKIPPED }
    data class Step(val label: String, val status: StepStatus = StepStatus.PENDING, val detail: String = "")

    sealed interface State {
        data object Idle : State
        data object Starting : State
        /** QR shown; [phoneStep] says what the phone is doing, if anything. */
        data class Waiting(val url: String, val hasOther: Boolean, val expiresAt: Long, val phoneStep: String?) : State
        data class Running(val steps: List<Step>) : State
        data class Finished(val ok: Boolean, val steps: List<Step>) : State
        data class Stopped(val reason: String) : State
    }

    private enum class Phase { FORM, ODYSSEUS, HA_URL, HA_SIGNIN, RUNNING, DONE }

    /** One setup run. Touched by the server thread and the HA job; guarded by `this`. */
    private class Session(
        val code: String,
        val oauthState: String,
        val hosts: List<String>,
        val tailscaleIp: String?,
        var hostIndex: Int = 0,
        var typedHost: String? = null,
        var name: String = "",
        var odysseus: String = "",
        var haUrl: String = "",
        var phase: Phase = Phase.FORM,
        var expiresAt: Long = 0,
        var badAttempts: Int = 0,
        var steps: List<Step> = emptyList(),
    ) {
        val qrHost get() = hosts[hostIndex]
        /** The host in Odysseus's return URL and HA's client_id/redirect_uri. */
        val host get() = typedHost ?: qrHost
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var session: Session? = null
    @Volatile private var config: APPConfig? = null
    @Volatile private var generation = 0

    /** No Home Assistant login on the device yet: the connection screen offers setup by itself. */
    fun needsSetup(config: APPConfig) = config.refreshToken.isBlank()

    fun start(appConfig: APPConfig) {
        stop("restarted")
        config = appConfig
        OdysseusLink.init(appConfig.context)
        val gen = ++generation
        _state.value = State.Starting
        scope.launch {
            val (ts, lan) = addresses()
            val reverse = ts?.let { ip ->
                val lookup = async { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull() }
                withTimeoutOrNull(3000) { lookup.await() }
            }
            val hosts = EchoSetupRules.chooseHosts(ts, reverse, lan)
            if (gen != generation) return@launch
            if (hosts.isEmpty()) {
                _state.value = State.Stopped("No network address: connect Wi-Fi or Tailscale first")
                return@launch
            }
            val socket = try {
                ServerSocket().apply { reuseAddress = true; soTimeout = 1000; bind(InetSocketAddress(PORT)) }
            } catch (e: Exception) {
                _state.value = State.Stopped("Couldn't open port $PORT: ${e.message}")
                return@launch
            }
            val s = Session(code = randomCode(), oauthState = randomCode(), hosts = hosts, tailscaleIp = ts,
                expiresAt = System.currentTimeMillis() + TIMEOUT_MS)
            if (gen != generation) { runCatching { socket.close() }; return@launch }
            server = socket
            session = s
            publish(s)
            Timber.i("Echo setup: serving on port $PORT (host ${s.qrHost})")
            serve(socket, s)
        }
    }

    /** The screen was left: stop, unless the Home Assistant job is running (it stops by itself after). */
    fun leave() {
        val s = session
        if (s != null && synchronized(s) { s.phase == Phase.RUNNING || s.phase == Phase.DONE }) return
        stop("Setup closed")
    }

    fun stop(reason: String = "Setup closed") {
        generation++
        val sock = server
        server = null
        session = null
        runCatching { sock?.close() }
        val st = _state.value
        if (st is State.Waiting || st is State.Starting) _state.value = State.Stopped(reason)
    }

    fun reset() { if (server == null && _state.value !is State.Running) _state.value = State.Idle }

    /** Show the other address (name/Wi-Fi/Tailscale) in the QR code; the code stays the same. */
    fun swapAddress() {
        val s = session ?: return
        synchronized(s) {
            if (s.hosts.size < 2 || s.phase != Phase.FORM) return
            s.hostIndex = (s.hostIndex + 1) % s.hosts.size
            s.typedHost = null
        }
        publish(s)
    }

    private fun randomCode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { random.nextBytes(it) })

    /** Tailscale (100.64.0.0/10) and Wi-Fi/Ethernet private IPv4 addresses. */
    private fun addresses(): Pair<String?, String?> {
        var tailscale: String? = null
        var lan: String? = null
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (EchoSetupRules.isTailscaleIp(ip)) tailscale = tailscale ?: ip
                    else if (EchoSetupRules.isPrivateIp(ip) && !nif.name.startsWith("tun")) lan = lan ?: ip
                }
            }
        }
        return tailscale to lan
    }

    private fun publish(s: Session) {
        if (session !== s) return
        _state.value = synchronized(s) {
            when (s.phase) {
                Phase.RUNNING -> State.Running(s.steps)
                Phase.DONE -> State.Finished(s.steps.none { it.status == StepStatus.FAILED }, s.steps)
                else -> State.Waiting(
                    url = EchoSetupRules.setupBase(s.qrHost, s.code),
                    hasOther = s.hosts.size > 1 && s.phase == Phase.FORM,
                    expiresAt = s.expiresAt,
                    phoneStep = when (s.phase) {
                        Phase.ODYSSEUS -> "Approving in Odysseus on the phone…"
                        Phase.HA_URL -> "Waiting for the Home Assistant address…"
                        Phase.HA_SIGNIN -> "Signing in to Home Assistant on the phone…"
                        else -> null
                    },
                )
            }
        }
    }

    // --- Server -----------------------------------------------------------------------------

    private fun serve(socket: ServerSocket, s: Session) {
        try {
            while (server === socket && !socket.isClosed) {
                val expired = synchronized(s) { System.currentTimeMillis() > s.expiresAt && s.phase != Phase.RUNNING }
                if (expired) {
                    val done = synchronized(s) { s.phase == Phase.DONE }
                    stop(if (done) "Setup finished" else "Setup timed out")
                    break
                }
                val client = try { socket.accept() } catch (_: SocketTimeoutException) { continue }
                client.use { runCatching { handle(it, s) }.onFailure { e -> Timber.w("Echo setup: request failed: ${e.javaClass.simpleName}") } }
            }
        } catch (e: Exception) {
            if (server === socket) stop("Setup stopped: ${e.message}")
        }
    }

    private class Request(val method: String, val path: String, val target: String, val form: Map<String, String>)

    private fun read(socket: Socket): Request? {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        var form = emptyMap<String, String>()
        if (parts[0] == "POST") {
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len !in 0..MAX_BODY) return null
            val body = ByteArray(len)
            var read = 0
            while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
            form = EchoSetupRules.parseForm(String(body, 0, read))
        }
        return Request(parts[0], parts[1].substringBefore('?'), parts[1], form)
    }

    private fun handle(socket: Socket, s: Session) {
        val req = read(socket) ?: return
        val out = socket.getOutputStream()
        val given = req.path.removePrefix("/setup/").substringBefore('/')
        if (!req.path.startsWith("/setup/") || !MessageDigest.isEqual(given.toByteArray(), s.code.toByteArray())) {
            if (req.path != "/favicon.ico") badAttempt(s)
            return respond(out, 404, EchoSetupPages.page("Not found",
                "<p>This setup link isn't valid any more. On the Echo, open Menu → Odysseus → Set up with phone for a new one.</p>"))
        }
        val base = "/setup/${s.code}"
        when ("${req.method} ${req.path.removePrefix(base)}") {
            "GET " -> respond(out, 200, mainPage(s))
            "POST " -> submitStart(out, s, req.form)
            "GET /odysseus" -> {
                // Odysseus hands the link over in the URL fragment (never sent to a server); this
                // page drops it from the address bar and posts it back here (EchoSetupPages).
                val nonce = randomCode()
                write(out, 200, "text/html; charset=utf-8", EchoSetupPages.odysseusLanding(s.code, nonce).toByteArray(),
                    csp = "default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$nonce'; base-uri 'none'; frame-ancestors 'none'")
            }
            "POST /odysseus" -> submitOdysseus(out, s, req.form)
            "POST /ha-url" -> submitHaUrl(out, s, req.form)
            "GET /ha" -> haCallback(out, s, EchoSetupRules.parseQuery(req.target))
            "GET /status" -> respondJson(out, statusJson(s))
            else -> respond(out, 404, EchoSetupPages.page("Not found", "<p>Unknown setup page.</p>"))
        }
    }

    private fun badAttempt(s: Session) {
        val n = synchronized(s) { ++s.badAttempts }
        if (n >= MAX_BAD_ATTEMPTS) stop("Setup stopped after $MAX_BAD_ATTEMPTS wrong requests")
    }

    // --- Steps of the phone flow -------------------------------------------------------------

    private fun mainPage(s: Session, error: String? = null): String = synchronized(s) {
        when (s.phase) {
            Phase.RUNNING, Phase.DONE -> EchoSetupPages.progress(s.steps, running = s.phase == Phase.RUNNING)
            Phase.HA_URL -> EchoSetupPages.haUrlForm(s.code, error, s.haUrl.ifEmpty { config?.homeAssistantURL.orEmpty() }, s.steps.firstOrNull())
            else -> EchoSetupPages.startForm(
                code = s.code,
                error = error,
                name = s.name.ifEmpty { config?.uuid.orEmpty() },
                odysseus = s.odysseus.ifEmpty { defaultOdysseus() },
                needsName = EchoSetupRules.hostNeedsName(s.qrHost),
                typedHost = s.typedHost.orEmpty(),
                tailscaleIp = s.tailscaleIp,
                haLink = if (s.phase == Phase.HA_SIGNIN) haAuthorizeUrl(s) else null,
            )
        }
    }

    private fun defaultOdysseus(): String =
        OdysseusLink.baseUrl.takeIf { it.startsWith("http") }?.let { EchoSetupRules.odysseusOrigin(it) }
            ?: BuildConfig.ODYSSEUS_DEFAULT_URL

    private fun haAuthorizeUrl(s: Session) = EchoSetupRules.haAuthorizeUrl(s.haUrl, s.host, s.code, s.oauthState)

    private fun submitStart(out: OutputStream, s: Session, form: Map<String, String>) {
        if (synchronized(s) { s.phase == Phase.RUNNING || s.phase == Phase.DONE }) return redirect(out, "/setup/${s.code}")
        val name = EchoSetupRules.cleanName(form["name"])
        val odysseusInput = form["odysseus"].orEmpty().trim()
        val odysseus = if (odysseusInput.isEmpty()) "" else EchoSetupRules.normalizeBaseUrl(odysseusInput)
        val needsName = EchoSetupRules.hostNeedsName(s.qrHost)
        val typed = if (needsName) EchoSetupRules.normalizeHostName(form["echo_host"])?.takeIf { '.' in it && EchoSetupRules.isLinkableName(it) } else null
        val error = when {
            name.isEmpty() -> "Enter a name for this Echo."
            odysseus == null -> "The Odysseus address isn't a web address (like https://odysseus.example.ts.net)."
            needsName && typed == null -> "Enter this Echo's Tailscale name (like echo-kitchen.example.ts.net): Home Assistant sign-in needs a name."
            else -> null
        }
        if (error != null) {
            synchronized(s) { s.name = name; s.odysseus = odysseusInput }
            return respond(out, 200, mainPage(s, error))
        }
        val target = synchronized(s) {
            s.name = name
            s.odysseus = odysseus!!
            s.typedHost = typed
            if (odysseus.isEmpty()) {
                s.steps = listOf(Step("Link to Odysseus", StepStatus.SKIPPED, "No Odysseus address given"))
                s.phase = Phase.HA_URL
                "/setup/${s.code}"
            } else {
                s.phase = Phase.ODYSSEUS
                EchoSetupRules.odysseusLinkUrl(odysseus, s.host, s.code, name)
            }
        }
        publish(s)
        redirect(out, target)
    }

    private fun submitOdysseus(out: OutputStream, s: Session, form: Map<String, String>) {
        if (synchronized(s) { s.phase == Phase.RUNNING || s.phase == Phase.DONE }) return redirect(out, "/setup/${s.code}")
        val baseUrl = EchoSetupRules.normalizeBaseUrl(form["base_url"])
        val token = form["token"].orEmpty().trim()
        if (baseUrl == null || !EchoSetupRules.isPlausibleToken(token)) {
            return respond(out, 400, EchoSetupPages.page("Link incomplete",
                "<p>Odysseus sent an incomplete link. <a href=\"/setup/${s.code}\">Start again</a>.</p>"))
        }
        OdysseusLink.baseUrl = baseUrl
        OdysseusLink.setToken(token)
        val test = runBlocking { OdysseusLink.fetchActions(System.currentTimeMillis() - 60_000L, 1) }
        val step = when (test) {
            is OdysseusLink.Result.Ok -> Step("Link to Odysseus", StepStatus.OK, "Odysseus answered")
            is OdysseusLink.Result.Failed -> Step("Link to Odysseus", StepStatus.WARN, "Saved, but the test failed: ${test.message}")
        }
        Timber.i("Echo setup: Odysseus link saved (${step.status})")
        val haUrl = EchoSetupRules.normalizeBaseUrl(form["ha_url"])
        val target = synchronized(s) {
            s.steps = listOf(step)
            if (haUrl != null) {
                s.haUrl = haUrl
                s.phase = Phase.HA_SIGNIN
                haAuthorizeUrl(s)
            } else {
                s.phase = Phase.HA_URL
                "/setup/${s.code}"
            }
        }
        publish(s)
        redirect(out, target)
    }

    private fun submitHaUrl(out: OutputStream, s: Session, form: Map<String, String>) {
        if (synchronized(s) { s.phase == Phase.RUNNING || s.phase == Phase.DONE }) return redirect(out, "/setup/${s.code}")
        val haUrl = EchoSetupRules.normalizeBaseUrl(form["ha_url"])
            ?: return respond(out, 200, EchoSetupPages.haUrlForm(s.code,
                "That isn't a web address (like http://homeassistant.local:8123).", form["ha_url"].orEmpty(),
                synchronized(s) { s.steps.firstOrNull() }))
        val target = synchronized(s) {
            s.haUrl = haUrl
            s.phase = Phase.HA_SIGNIN
            haAuthorizeUrl(s)
        }
        publish(s)
        redirect(out, target)
    }

    private fun haCallback(out: OutputStream, s: Session, query: Map<String, String>) {
        val (phase, haUrl, clientId) = synchronized(s) { Triple(s.phase, s.haUrl, EchoSetupRules.haClientId(s.host)) }
        if (phase == Phase.RUNNING || phase == Phase.DONE) return redirect(out, "/setup/${s.code}")
        if (phase != Phase.HA_SIGNIN || haUrl.isEmpty()) return redirect(out, "/setup/${s.code}")
        val state = query["state"].orEmpty()
        if (!MessageDigest.isEqual(state.toByteArray(), s.oauthState.toByteArray())) {
            return respond(out, 400, EchoSetupPages.page("Sign-in not for this setup",
                "<p>This sign-in doesn't belong to this setup. <a href=\"${esc(haAuthorizeUrl(s))}\">Sign in again</a>.</p>"))
        }
        val code = query["code"].orEmpty()
        if (code.isEmpty()) {
            val why = query["error"]?.let { " (${esc(it.take(80))})" } ?: ""
            return respond(out, 200, EchoSetupPages.page("Sign-in cancelled",
                "<p>Home Assistant didn't sign you in$why. <a href=\"${esc(haAuthorizeUrl(s))}\">Try again</a>.</p>"))
        }
        val cfg = config ?: return respond(out, 500, EchoSetupPages.page("Error", "<p>The Echo isn't ready. Start again.</p>"))
        val tokens = try {
            HaSetupClient(haUrl).exchangeCode(code, clientId)
        } catch (e: Exception) {
            val msg = (e as? HaSetupException)?.message ?: "Couldn't reach Home Assistant at $haUrl (${e.javaClass.simpleName})"
            return respond(out, 200, EchoSetupPages.page("Sign-in failed",
                "<p class=err>${esc(msg)}</p><p><a href=\"${esc(haAuthorizeUrl(s))}\">Try again</a></p>"))
        }
        // Stored the way VACA stores its own login, plus the client id refresh needs.
        cfg.homeAssistantURL = haUrl
        HaClientId.set(cfg.context, clientId)
        cfg.refreshToken = tokens.refreshToken
        cfg.accessToken = tokens.accessToken
        cfg.tokenExpiry = System.currentTimeMillis() + tokens.expiresInSeconds * 1000
        Timber.i("Echo setup: Home Assistant sign-in saved for $haUrl")
        synchronized(s) {
            s.steps = (s.steps.take(1).ifEmpty { listOf(Step("Link to Odysseus", StepStatus.SKIPPED)) }) +
                Step("Sign in to Home Assistant", StepStatus.OK, haUrl) +
                JOB_STEPS.map { Step(it) }
            s.phase = Phase.RUNNING
        }
        publish(s)
        scope.launch { runHaJob(s, cfg, haUrl, tokens.accessToken) }
        redirect(out, "/setup/${s.code}")
    }

    // --- The Home Assistant job ----------------------------------------------------------------

    private const val ADD = "Add this Echo to Home Assistant"
    private const val HA_URL_OPTION = "Give the Echo the Home Assistant address"
    private const val WAKE_WORD = "Wake word: ${EchoSetupRules.WAKE_WORD}"
    private const val THRESHOLD = "Wake word threshold: 9.2"
    private const val SOUND = "Wake sound: ${EchoSetupRules.WAKE_SOUND}"
    private val JOB_STEPS = listOf(ADD, HA_URL_OPTION, WAKE_WORD, THRESHOLD, SOUND)

    private fun update(s: Session, label: String, status: StepStatus, detail: String = "") {
        synchronized(s) { s.steps = s.steps.map { if (it.label == label) Step(label, status, detail) else it } }
        publish(s)
    }

    private fun attempt(s: Session, label: String, block: () -> String): Boolean {
        update(s, label, StepStatus.RUNNING)
        return try {
            update(s, label, StepStatus.OK, block()); true
        } catch (e: Exception) {
            val msg = (e as? HaSetupException)?.message ?: "Couldn't reach Home Assistant (${e.javaClass.simpleName})"
            Timber.w("Echo setup: $label failed: $msg")
            update(s, label, StepStatus.FAILED, msg); false
        }
    }

    private fun runHaJob(s: Session, cfg: APPConfig, haUrl: String, token: String) {
        val ha = HaSetupClient(haUrl)
        val (name, host, tailscaleIp) = synchronized(s) { Triple(s.name, s.host, s.tailscaleIp) }
        // VACA's device id is the satellite name HA shows ("VACA <id>"); the setup's name becomes it.
        val oldTitle = "VACA ${cfg.uuid}"
        EchoSetupRules.deviceIdFromName(name).takeIf { it.isNotEmpty() && it != cfg.uuid }?.let {
            Timber.i("Echo setup: device id ${cfg.uuid} -> $it")
            cfg.uuid = it
        }
        val titles = listOf("VACA ${cfg.uuid}", oldTitle).distinct()

        var entryId: String? = null
        attempt(s, ADD) {
            val entry = try {
                ha.addSelf(token, host, EchoSetupRules.WYOMING_PORT, titles)
            } catch (e: HaSetupException) {
                // HA may not resolve the MagicDNS name; its Tailscale IP works without DNS.
                if (!e.cannotConnect || tailscaleIp == null || host == tailscaleIp) throw e
                ha.addSelf(token, tailscaleIp, EchoSetupRules.WYOMING_PORT, titles)
            }
            entryId = entry.entryId
            if (entry.created) "Added as “${entry.title}”" else "Already added as “${entry.title}”"
        }
        val id = entryId
        if (id == null) {
            listOf(HA_URL_OPTION, WAKE_WORD, THRESHOLD, SOUND).forEach { update(s, it, StepStatus.SKIPPED, "Needs the Echo in Home Assistant") }
        } else {
            attempt(s, HA_URL_OPTION) { ha.setHaUrlOption(token, id, haUrl); haUrl }
            setControls(s, ha, token, id)
        }
        synchronized(s) {
            s.phase = Phase.DONE
            s.expiresAt = System.currentTimeMillis() + GRACE_MS
        }
        publish(s)
        Timber.i("Echo setup: finished")
    }

    private fun setControls(s: Session, ha: HaSetupClient, token: String, entryId: String) {
        val controls = listOf(WAKE_WORD, THRESHOLD, SOUND)
        controls.forEach { update(s, it, StepStatus.RUNNING, "Waiting for the Echo's controls in Home Assistant…") }
        var e = try {
            ha.waitForEntities(token, entryId, 60_000) {
                EchoSetupRules.isAvailable(it.wakeWord) && EchoSetupRules.isAvailable(it.threshold) && EchoSetupRules.isAvailable(it.wakeSound)
            }
        } catch (ex: Exception) {
            controls.forEach { c -> update(s, c, StepStatus.FAILED, ex.message ?: "Couldn't read the Echo's controls") }
            return
        }
        val wanted = { v: EchoSetupRules.VacaEntities ->
            EchoSetupRules.pickOption(v.wakeWord?.options.orEmpty(), EchoSetupRules.WAKE_WORD) != null &&
                EchoSetupRules.pickOption(v.wakeSound?.options.orEmpty(), EchoSetupRules.WAKE_SOUND) != null
        }
        if (!wanted(e)) {
            // Hey Athena is a microWakeWord model: VACA lists it only for that engine.
            val engine = e.wakeWordEngine
            if (EchoSetupRules.pickOption(e.wakeWord?.options.orEmpty(), EchoSetupRules.WAKE_WORD) == null &&
                EchoSetupRules.isAvailable(engine) && engine!!.state != EchoSetupRules.WAKE_WORD_ENGINE
            ) {
                runCatching {
                    ha.selectOption(token, engine.entityId,
                        EchoSetupRules.pickOption(engine.options, EchoSetupRules.WAKE_WORD_ENGINE) ?: EchoSetupRules.WAKE_WORD_ENGINE)
                }.onFailure { Timber.w("Echo setup: couldn't switch the wake word engine") }
            }
            e = runCatching { ha.waitForEntities(token, entryId, 20_000, wanted) }.getOrDefault(e)
        }
        val missing = "Not found in Home Assistant. Is the Echo connected (Settings → Devices & services)?"
        attempt(s, WAKE_WORD) {
            val ent = e.wakeWord?.takeIf { EchoSetupRules.isAvailable(it) } ?: throw HaSetupException(missing)
            val opt = EchoSetupRules.pickOption(ent.options, EchoSetupRules.WAKE_WORD)
                ?: throw HaSetupException("${ent.entityId} doesn't offer ${EchoSetupRules.WAKE_WORD} (wake word engine must be microWakeWord)")
            ha.selectOption(token, ent.entityId, opt); ent.entityId
        }
        attempt(s, THRESHOLD) {
            val ent = e.threshold?.takeIf { EchoSetupRules.isAvailable(it) } ?: throw HaSetupException(missing)
            ha.setNumber(token, ent.entityId, EchoSetupRules.WAKE_THRESHOLD); ent.entityId
        }
        attempt(s, SOUND) {
            val ent = e.wakeSound?.takeIf { EchoSetupRules.isAvailable(it) } ?: throw HaSetupException(missing)
            val opt = EchoSetupRules.pickOption(ent.options, EchoSetupRules.WAKE_SOUND)
                ?: throw HaSetupException("${ent.entityId} doesn't offer ${EchoSetupRules.WAKE_SOUND}")
            ha.selectOption(token, ent.entityId, opt); ent.entityId
        }
    }

    private fun statusJson(s: Session): String = synchronized(s) {
        buildJsonObject {
            put("phase", s.phase.name.lowercase())
            putJsonArray("steps") {
                s.steps.forEach { st -> addJsonObject { put("label", st.label); put("status", st.status.name.lowercase()); put("detail", st.detail) } }
            }
        }.toString()
    }

    // --- HTTP helpers ------------------------------------------------------------------------

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private const val SECURITY_HEADERS = "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
        "X-Frame-Options: DENY\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n"
    private const val CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"

    private val REASONS = mapOf(200 to "OK", 303 to "See Other", 400 to "Bad Request", 404 to "Not Found", 500 to "Internal Server Error")

    private fun write(out: OutputStream, status: Int, type: String, body: ByteArray, extra: String = "", csp: String = CSP) {
        out.write(("HTTP/1.1 $status ${REASONS[status] ?: "OK"}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n${extra}Content-Security-Policy: $csp\r\n$SECURITY_HEADERS\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun respond(out: OutputStream, status: Int, html: String) =
        write(out, status, "text/html; charset=utf-8", html.toByteArray())

    private fun respondJson(out: OutputStream, json: String) =
        write(out, 200, "application/json", json.toByteArray())

    private fun redirect(out: OutputStream, location: String) =
        write(out, 303, "text/html; charset=utf-8",
            EchoSetupPages.page("Continue", "<p><a href=\"${esc(location)}\">Continue</a></p>").toByteArray(),
            "Location: ${location.replace("\r", "").replace("\n", "")}\r\n")
}
