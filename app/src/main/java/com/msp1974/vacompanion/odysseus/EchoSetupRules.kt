package com.msp1974.vacompanion.odysseus

// Odysseus fork: the pure rules of the phone setup (EchoSetup.kt): which address the Echo
// puts in its QR code, URL and form checks, the Odysseus and Home Assistant links it sends the
// phone to, and how it finds its own controls in Home Assistant. No Android code here, so it's
// unit-tested on the JVM (app/src/test/.../odysseus/EchoSetupRulesTest.kt).

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object EchoSetupRules {
    const val SETUP_PORT = 10801
    const val WYOMING_PORT = 10800
    const val MAX_NAME = 40

    /** What the setup sets on the Echo's controls in Home Assistant. */
    const val WAKE_WORD = "Hey Athena"
    const val WAKE_WORD_ENGINE = "microwakeword"
    const val WAKE_THRESHOLD = 9.2
    const val WAKE_SOUND = "Ding"

    private val CODE = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun isValidCode(code: String) = CODE.matches(code)

    // --- Addresses ------------------------------------------------------------------------

    private fun octets(ip: String): List<Int>? =
        IPV4.matchEntire(ip)?.groupValues?.drop(1)?.map { it.toInt() }?.takeIf { o -> o.all { it in 0..255 } }

    fun isIpv4(host: String) = octets(host) != null

    /** 100.64.0.0/10, Tailscale's range. */
    fun isTailscaleIp(ip: String): Boolean = octets(ip)?.let { it[0] == 100 && it[1] in 64..127 } ?: false

    /** 10/8, 172.16/12, 192.168/16: what Home Assistant accepts in a client_id. */
    fun isPrivateIp(ip: String): Boolean = octets(ip)?.let {
        it[0] == 10 || (it[0] == 172 && it[1] in 16..31) || (it[0] == 192 && it[1] == 168)
    } ?: false

    /** A DNS name (one or more labels), lower-cased and without a trailing dot; null if invalid. */
    fun normalizeHostName(input: String?): String? {
        val name = input?.trim()?.trimEnd('.')?.lowercase() ?: return null
        if (name.isEmpty() || name.length > 253 || isIpv4(name)) return null
        return name.takeIf { n -> n.split('.').all { LABEL.matches(it) } }
    }

    /** Host names Odysseus accepts in the return URL: *.ts.net, *.local, *.lan, *.home.arpa or one label. */
    fun isLinkableName(name: String): Boolean = name != "localhost" &&
        ('.' !in name || listOf(".ts.net", ".local", ".lan", ".home.arpa").any { name.endsWith(it) })

    /** Home Assistant rejects a client_id on a public or Tailscale IP: it needs a name or a private IP. */
    fun hostNeedsName(host: String) = isIpv4(host) && !isPrivateIp(host)

    /**
     * The Echo's addresses, best first: the Tailscale MagicDNS name (when the device has a
     * Tailscale IP and its reverse lookup gave a dotted name), then the Wi-Fi/LAN IP, then the raw
     * Tailscale IP (which needs a typed name before Home Assistant sign-in).
     */
    fun chooseHosts(tailscaleIp: String?, reverseName: String?, lanIp: String?): List<String> {
        val name = if (tailscaleIp != null) normalizeHostName(reverseName)?.takeIf { '.' in it && isLinkableName(it) } else null
        return when {
            name != null -> listOfNotNull(name, lanIp)
            lanIp != null -> listOfNotNull(lanIp, tailscaleIp)
            else -> listOfNotNull(tailscaleIp)
        }
    }

    // --- URLs -----------------------------------------------------------------------------

    /**
     * An http(s) address typed or received: "scheme://host[:port][/path]" without a trailing
     * slash, or null when it isn't one. A bare "host" gets https://. No user info, query or fragment.
     */
    fun normalizeBaseUrl(input: String?): String? {
        var s = input?.trim() ?: return null
        if (s.isEmpty() || s.length > 512 || s.any { it.isWhitespace() || it.code < 0x20 }) return null
        if (!s.contains("://")) {
            // A bare "host" is https; "host:8123" (any port but 443) is plain http, as HA serves it.
            val port = s.substringBefore('/').substringAfterLast(':', "").toIntOrNull()
            s = (if (port != null && port != 443) "http://" else "https://") + s
        }
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (host.isEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        val hostPart = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = (uri.rawPath ?: "").trimEnd('/')
        return "$scheme://$hostPart$port$path"
    }

    const val VOICE_PATH = "/api/chat/voice/v1"

    /** "https://odysseus.x.ts.net/api/chat/voice/v1" -> "https://odysseus.x.ts.net". */
    fun odysseusOrigin(voiceBaseUrl: String): String = voiceBaseUrl.trimEnd('/').removeSuffix(VOICE_PATH)

    fun setupBase(host: String, code: String) = "http://${hostForUrl(host)}:$SETUP_PORT/setup/$code"

    private fun hostForUrl(host: String) = if (':' in host) "[$host]" else host

    /** client_id Home Assistant ties the Echo's tokens to; refresh must send the same one. */
    fun haClientId(host: String) = "http://${hostForUrl(host)}:$SETUP_PORT/"

    fun haRedirectUri(host: String, code: String) = "${setupBase(host, code)}/ha"

    fun haAuthorizeUrl(haUrl: String, host: String, code: String, state: String): String =
        "$haUrl/auth/authorize?response_type=code" +
            "&client_id=${enc(haClientId(host))}" +
            "&redirect_uri=${enc(haRedirectUri(host, code))}" +
            "&state=${enc(state)}"

    fun odysseusLinkUrl(odysseus: String, host: String, code: String, name: String): String =
        "$odysseus/echo/link?return=${enc("${setupBase(host, code)}/odysseus")}&name=${enc(name)}"

    // --- Forms ----------------------------------------------------------------------------

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** application/x-www-form-urlencoded; the first value of a repeated field wins. */
    fun parseForm(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in body.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val kv = runCatching {
                URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }.getOrNull() ?: continue
            out.putIfAbsent(kv.first, kv.second)
        }
        return out
    }

    /** Query string of a request target ("/path?a=1&b=2"). */
    fun parseQuery(target: String): Map<String, String> = parseForm(target.substringAfter('?', ""))

    /** The name shown in Odysseus: printable, single spaces, at most MAX_NAME characters. */
    fun cleanName(input: String?): String =
        (input ?: "").filter { !it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()

    /** VACA's device id from a name: letters, digits, "_" and "-" (as its own dialog allows), ≤ 32. */
    fun deviceIdFromName(name: String): String =
        cleanName(name).replace(' ', '_').filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(32)

    /** A voice token as Odysseus mints it: visible ASCII, no spaces, at most 512 characters. */
    fun isPlausibleToken(token: String) = token.length in 8..512 && token.all { it.code in 0x21..0x7e }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
        }
    }

    // --- Home Assistant entities -----------------------------------------------------------

    data class HaEntity(val entityId: String, val name: String?, val state: String?, val options: List<String>)

    data class VacaEntities(
        val wakeWord: HaEntity?,
        val wakeWordEngine: HaEntity?,
        val wakeSound: HaEntity?,
        val threshold: HaEntity?,
    )

    /** Template listing the entities of one VACA config entry as JSON. */
    fun entitiesTemplate(entryId: String): String {
        require(Regex("^[A-Za-z0-9_-]{1,64}$").matches(entryId)) { "bad entry id" }
        return "{% set ns = namespace(r=[]) %}" +
            "{% for e in integration_entities('vaca') %}{% if config_entry_id(e) == '$entryId' %}" +
            "{% set ns.r = ns.r + [{'entity_id': e, 'name': state_attr(e, 'friendly_name'), " +
            "'state': states(e), 'options': state_attr(e, 'options')}] %}" +
            "{% endif %}{% endfor %}{{ ns.r | to_json }}"
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parseEntities(rendered: String): List<HaEntity> {
        val arr = runCatching { json.parseToJsonElement(rendered.trim()) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["entity_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            HaEntity(
                entityId = id,
                name = (o["name"] as? JsonPrimitive)?.contentOrNull,
                state = (o["state"] as? JsonPrimitive)?.contentOrNull,
                options = (o["options"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            )
        }
    }

    /**
     * Picks VACA's controls by their key (VACA names entity ids "<device>_<key>", HA may add
     * "_2"), falling back to the English names when an entity id was renamed.
     */
    fun matchEntities(entities: List<HaEntity>): VacaEntities {
        fun objectId(e: HaEntity) = e.entityId.substringAfter('.').replace(Regex("_\\d+$"), "")
        fun find(domain: String, key: String, englishName: String): HaEntity? {
            val inDomain = entities.filter { it.entityId.startsWith("$domain.") }
            return inDomain.firstOrNull { objectId(it) == key || objectId(it).endsWith("_$key") }
                ?: inDomain.firstOrNull { it.name?.trim()?.endsWith(englishName, ignoreCase = true) == true }
        }
        return VacaEntities(
            wakeWord = find("select", "wake_word", " Wake word"),
            wakeWordEngine = find("select", "wake_word_engine", " Wake word engine"),
            wakeSound = find("select", "wake_word_sound", " Wake word sound"),
            threshold = find("number", "wake_word_threshold", " Wake word threshold"),
        )
    }

    /** The option as Home Assistant spells it, matched without regard to case, "_" or spaces. */
    fun pickOption(options: List<String>, wanted: String): String? {
        fun norm(s: String) = s.lowercase().replace('_', ' ').trim()
        return options.firstOrNull { norm(it) == norm(wanted) }
    }

    fun isAvailable(e: HaEntity?) = e != null && e.state != null && e.state != "unavailable"
}
