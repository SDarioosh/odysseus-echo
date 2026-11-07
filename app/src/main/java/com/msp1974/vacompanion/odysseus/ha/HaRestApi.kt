package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: HaApi over Home Assistant's REST API (blocking HaHttp on Dispatchers.IO):
//   POST /api/template                              template()
//   POST /api/services/<domain>/<service>           call() (?return_response only when asked:
//                                                   HA answers 400 to it for a service without one)
//   GET  /api/states                                states()
//   GET  <path>                                     bytes() (≤ 8 MB)
// Every request goes through withAuthRetry: one token refresh on a 401, no more.
// HaClient uses it for everything the websocket doesn't do. Tested with a fake HaHttp
// (HaRestApiTest).

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext

class HaRestApi(
    private val auth: HaAuth,
    private val http: HaHttp = UrlConnectionHaHttp.withMaxBody(MAX_BODY),
    private val bytesHttp: HaBytesHttp = UrlConnectionHaHttp,
    private val io: CoroutineContext = Dispatchers.IO,
    private val pollMs: Long = 5_000L,
) : HaApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun template(template: String): String {
        val body = buildJsonObject { put("template", template) }.toString()
        return send("POST", "/api/template", body).body
    }

    override suspend fun call(domain: String, service: String, data: JsonObject, returnResponse: Boolean): JsonElement? {
        require(NAME.matches(domain) && NAME.matches(service)) { "bad service name $domain.$service" }
        val path = "/api/services/$domain/$service" + if (returnResponse) "?return_response" else ""
        val r = send("POST", path, data.toString())
        if (!returnResponse) return null
        val o = runCatching { json.parseToJsonElement(r.body) as? JsonObject }.getOrNull()
            ?: throw HaException("Unexpected answer from Home Assistant to $domain.$service")
        return o["service_response"] ?: JsonNull
    }

    override suspend fun states(filter: (HaState) -> Boolean): List<HaState> =
        HaEntities.fromRestList(send("GET", "/api/states", null).body).filter(filter)

    /** REST only: polls GET /api/states every 5 s. HaClient's watch() uses the websocket. */
    override fun watch(entityIds: Set<String>): Flow<Map<String, HaState>> = flow {
        while (true) {
            emit(states { it.entityId in entityIds }.associateBy { it.entityId })
            delay(pollMs)
        }
    }

    override suspend fun bytes(path: String): ByteArray {
        checkPath(path)
        return withContext(io) {
            withAuthRetry(auth) { token ->
                val r = bytesHttp.get(auth.baseUrl() + path, token, MAX_BYTES)
                if (r.code !in 200..299) throw HaException("Home Assistant answered HTTP ${r.code} to $path", r.code)
                r.bytes
            }
        }
    }

    private suspend fun send(method: String, path: String, body: String?): HaResponse = withContext(io) {
        withAuthRetry(auth) { token ->
            val r = http.call(method, auth.baseUrl() + path, token, if (body != null) "application/json" else null, body)
            if (r.code !in 200..299) throw HaException(errorText(path, r), r.code)
            r
        }
    }

    private fun errorText(path: String, r: HaResponse): String {
        val message = runCatching {
            ((json.parseToJsonElement(r.body) as? JsonObject)?.get("message") as? JsonPrimitive)?.content
        }.getOrNull()
        return "Home Assistant answered HTTP ${r.code} to ${path.substringBefore('?')}" +
            if (message.isNullOrBlank()) "" else ": ${message.take(200)}"
    }

    companion object {
        /** Body cap for REST answers: a state list or a library listing can be large. */
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_BYTES = 8 * 1024 * 1024
        private val NAME = Regex("[a-z0-9_]+")

        /** A path on Home Assistant itself: starts with one "/", no scheme, host or "..". */
        fun checkPath(path: String) {
            if (!path.startsWith("/") || path.startsWith("//") || path.contains("://") ||
                path.contains("..") || path.contains('\\')
            ) {
                throw HaException("Not a Home Assistant path: $path")
            }
        }
    }
}
