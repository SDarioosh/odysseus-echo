package com.msp1974.vacompanion.odysseus.ha

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class HaState(
    val entityId: String,
    val state: String,                  // "playing", "paused", "idle", "off", "unavailable", …
    val attributes: JsonObject,
    val lastUpdatedMs: Long,
)

class HaException(message: String, val code: Int = 0) : Exception(message)

interface HaApi {
    /** POST /api/template (admin token). Never put user text in a template. */
    suspend fun template(template: String): String
    /** A service call. With [returnResponse] the `service_response` (REST) / `response` (ws). */
    suspend fun call(domain: String, service: String, data: JsonObject,
                     returnResponse: Boolean = false): JsonElement?
    /** GET /api/states, filtered. */
    suspend fun states(filter: (HaState) -> Boolean = { true }): List<HaState>
    /** Live states of [entityIds] (websocket subscribe_entities; REST polling every 5 s
     *  as the fallback). Hot while collected; closes the socket when nobody collects. */
    fun watch(entityIds: Set<String>): Flow<Map<String, HaState>>
    /** GET of a HA path with the bearer header (e.g. /api/media_player_proxy/…), ≤ 8 MB. */
    suspend fun bytes(path: String): ByteArray
}
