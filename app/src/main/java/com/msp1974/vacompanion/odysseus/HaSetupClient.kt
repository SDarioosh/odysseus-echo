package com.msp1974.vacompanion.odysseus

// Odysseus fork: the Home Assistant calls of the phone setup (EchoSetup.kt), REST only, with the
// owner's admin access token from the sign-in on the phone:
//   POST /auth/token                                    code -> tokens (client_id = the Echo's)
//   GET  /api/config/config_entries/entry?domain=vaca   is this Echo already added?
//   POST /api/config/config_entries/flow                {"handler":"vaca"} -> step "user"
//   POST /api/config/config_entries/flow/<id>           {"host":…, "port":10800}
//   POST /api/config/config_entries/options/flow(/<id>) options "ha_url" (keeps default_dashboard)
//   POST /api/template                                  the entry's entities (EchoSetupRules)
//   POST /api/services/select/select_option, number/set_value
// HTTP goes through HaHttp so the steps are unit-tested with a fake.

import com.msp1974.vacompanion.odysseus.EchoSetupRules.HaEntity
import com.msp1974.vacompanion.odysseus.EchoSetupRules.VacaEntities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

data class HaResponse(val code: Int, val body: String)

fun interface HaHttp {
    /** [contentType] null = no body. Throws on network errors. */
    fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse
}

/** Plain HttpURLConnection; certificates are checked by the platform as usual. */
object UrlConnectionHaHttp : HaHttp {
    override fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null && contentType != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText().take(256 * 1024) } ?: ""
            return HaResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

class HaSetupException(message: String, val cannotConnect: Boolean = false) : Exception(message)

data class HaTokens(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

class HaSetupClient(
    private val haUrl: String,
    private val http: HaHttp = UrlConnectionHaHttp,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun post(path: String, token: String, body: JsonElement): HaResponse =
        http.call("POST", haUrl + path, token, "application/json", body.toString())

    private fun failed(what: String, r: HaResponse): HaSetupException = HaSetupException(
        when (r.code) {
            401 -> "$what: Home Assistant refused the login (401)"
            403 -> "$what: this needs an administrator account (403)"
            404 -> "$what: not found in Home Assistant (404). Is the View Assist Companion App integration installed?"
            else -> "$what: Home Assistant answered HTTP ${r.code}"
        }
    )

    // --- Sign-in --------------------------------------------------------------------------

    fun exchangeCode(code: String, clientId: String): HaTokens {
        val form = "grant_type=authorization_code&code=${EchoSetupRules.enc(code)}&client_id=${EchoSetupRules.enc(clientId)}"
        val r = http.call("POST", "$haUrl/auth/token", null, "application/x-www-form-urlencoded", form)
        val o = obj(r.body)
        if (r.code != 200 || o == null) {
            val err = o?.str("error_description") ?: o?.str("error")
            throw HaSetupException("Home Assistant didn't accept the sign-in (HTTP ${r.code}${if (err != null) ": $err" else ""})")
        }
        val access = o.str("access_token")
        val refresh = o.str("refresh_token")
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) throw HaSetupException("Home Assistant sent no tokens")
        val expires = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 1800L
        return HaTokens(access, refresh, expires)
    }

    // --- The config entry -----------------------------------------------------------------

    data class Entry(val entryId: String, val title: String, val created: Boolean)

    /** Existing VACA entries that aren't ignored or disabled: (entry_id, title). */
    fun vacaEntries(token: String): List<Pair<String, String>> {
        val r = http.call("GET", "$haUrl/api/config/config_entries/entry?domain=vaca", token, null, null)
        if (r.code != 200) throw failed("Listing Home Assistant integrations", r)
        val arr = runCatching { json.parseToJsonElement(r.body) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.str("source") == "ignore" || o.str("disabled_by") != null) return@mapNotNull null
            val id = o.str("entry_id") ?: return@mapNotNull null
            id to (o.str("title") ?: "")
        }
    }

    /**
     * Adds this Echo (Wyoming at [host]:[port]) unless an entry titled one of [titles] exists.
     * VACA's "user" step makes a new entry every time, so the title check is what keeps a re-run
     * from adding the Echo twice.
     */
    fun addSelf(token: String, host: String, port: Int, titles: List<String>): Entry {
        fun existing() = vacaEntries(token).firstOrNull { (_, t) -> titles.any { it.equals(t, ignoreCase = true) } }
        existing()?.let { return Entry(it.first, it.second, created = false) }

        val start = post("/api/config/config_entries/flow", token, buildJsonObject {
            put("handler", "vaca"); put("show_advanced_options", false)
        })
        if (start.code != 200) throw failed("Starting the integration setup", start)
        val form = obj(start.body) ?: throw HaSetupException("Unexpected answer from Home Assistant")
        if (form.str("type") == "abort") return abortResult(form, ::existing)
        val flowId = form.str("flow_id") ?: throw HaSetupException("Home Assistant sent no flow id")
        if (form.str("type") != "form" || form.str("step_id") != "user") {
            throw HaSetupException("Unexpected setup step in Home Assistant: ${form.str("step_id") ?: form.str("type")}")
        }

        val r = post("/api/config/config_entries/flow/$flowId", token, buildJsonObject {
            put("host", host); put("port", port)
        })
        if (r.code != 200) throw failed("Adding the Echo", r)
        val res = obj(r.body) ?: throw HaSetupException("Unexpected answer from Home Assistant")
        return when (res.str("type")) {
            "create_entry" -> {
                val entry = res["result"] as? JsonObject
                val id = entry?.str("entry_id") ?: existing()?.first
                    ?: throw HaSetupException("Home Assistant added the Echo but didn't say under which id")
                Entry(id, entry?.str("title") ?: res.str("title") ?: "", created = true)
            }
            "abort" -> abortResult(res, ::existing)
            "form" -> {
                val err = (res["errors"] as? JsonObject)?.str("base")
                if (err == "cannot_connect") throw HaSetupException(
                    "Home Assistant couldn't reach this Echo at $host:$port. Is the Echo reachable from Home Assistant (same network or Tailscale)?",
                    cannotConnect = true,
                )
                throw HaSetupException("Home Assistant asked again for the address (${err ?: "no reason given"})")
            }
            else -> throw HaSetupException("Unexpected answer from Home Assistant: ${res.str("type")}")
        }
    }

    private fun abortResult(res: JsonObject, existing: () -> Pair<String, String>?): Entry {
        val reason = res.str("reason") ?: "unknown"
        if (reason == "already_configured") existing()?.let { return Entry(it.first, it.second, created = false) }
        throw HaSetupException(
            if (reason == "already_configured") "Home Assistant says this Echo is already added, but under another name. Remove the old entry in Settings → Devices & services → View Assist Companion App and run setup again."
            else "Home Assistant stopped the setup: $reason"
        )
    }

    /** Sets option "ha_url" through the options flow, keeping "default_dashboard". */
    fun setHaUrlOption(token: String, entryId: String, url: String) {
        val start = post("/api/config/config_entries/options/flow", token, buildJsonObject { put("handler", entryId) })
        if (start.code != 200) throw failed("Opening the Echo's options", start)
        val form = obj(start.body) ?: throw HaSetupException("Unexpected answer from Home Assistant")
        val flowId = form.str("flow_id") ?: throw HaSetupException("Home Assistant sent no options flow id")
        val dashboard = suggestedValue(form, "default_dashboard")
        val r = post("/api/config/config_entries/options/flow/$flowId", token, buildJsonObject {
            put("ha_url", url)
            if (!dashboard.isNullOrEmpty()) put("default_dashboard", dashboard)
        })
        if (r.code != 200 || obj(r.body)?.str("type") != "create_entry") throw failed("Saving the Echo's options", r)
    }

    private fun suggestedValue(form: JsonObject, field: String): String? =
        (form["data_schema"] as? JsonArray)?.firstNotNullOfOrNull { el ->
            val o = el as? JsonObject ?: return@firstNotNullOfOrNull null
            if (o.str("name") != field) return@firstNotNullOfOrNull null
            (o["description"] as? JsonObject)?.str("suggested_value")
        }

    // --- Entities -------------------------------------------------------------------------

    fun entities(token: String, entryId: String): List<HaEntity> {
        val r = post("/api/template", token, buildJsonObject { put("template", EchoSetupRules.entitiesTemplate(entryId)) })
        if (r.code != 200) throw failed("Reading the Echo's controls", r)
        return EchoSetupRules.parseEntities(r.body)
    }

    /**
     * Waits until the entry's controls exist and are available, and until [ready] says the
     * options it needs are there. Returns what it has when [timeoutMs] runs out.
     */
    fun waitForEntities(token: String, entryId: String, timeoutMs: Long, ready: (VacaEntities) -> Boolean): VacaEntities {
        val end = now() + timeoutMs
        var last = VacaEntities(null, null, null, null)
        while (true) {
            last = runCatching { EchoSetupRules.matchEntities(entities(token, entryId)) }.getOrElse { e ->
                if (e is HaSetupException && now() >= end) throw e else last
            }
            if (ready(last) || now() >= end) return last
            sleep(2000)
        }
    }

    fun selectOption(token: String, entityId: String, option: String) {
        val r = post("/api/services/select/select_option", token, buildJsonObject {
            put("entity_id", entityId); put("option", option)
        })
        if (r.code != 200) throw failed("Setting $entityId", r)
    }

    fun setNumber(token: String, entityId: String, value: Double) {
        val r = post("/api/services/number/set_value", token, buildJsonObject {
            put("entity_id", entityId); put("value", value)
        })
        if (r.code != 200) throw failed("Setting $entityId", r)
    }
}
