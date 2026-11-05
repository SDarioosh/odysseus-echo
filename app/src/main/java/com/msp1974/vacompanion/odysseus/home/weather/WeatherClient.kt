package com.msp1974.vacompanion.odysseus.home.weather

// Odysseus fork: reads the weather for the clock page from Home Assistant. Read-only, REST, with
// the access token the Echo already holds for the dashboard:
//   POST /api/template                                        weather entities; current conditions
//   POST /api/services/weather/get_forecasts?return_response  today's high and low
// HTTP goes through HaHttp so it is unit-tested with a fake (WeatherClientTest).

import com.msp1974.vacompanion.odysseus.HaHttp
import com.msp1974.vacompanion.odysseus.HaResponse
import com.msp1974.vacompanion.odysseus.UrlConnectionHaHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

/** A weather entity offered in the settings. [available] = not unavailable/unknown. */
data class WeatherEntity(val entityId: String, val name: String, val available: Boolean)

/** What the clock page shows. [high]/[low] are null when the forecast doesn't give them. */
data class WeatherNow(
    val entityId: String,
    val condition: String,
    val temperature: Double?,
    val unit: String,
    val isNight: Boolean,
    val high: Double? = null,
    val low: Double? = null,
)

class WeatherException(message: String, val code: Int = 0) : Exception(message)

class WeatherClient(
    private val haUrl: String,
    private val http: HaHttp = UrlConnectionHaHttp,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun template(token: String, template: String): String {
        val body = buildJsonObject { put("template", template) }.toString()
        val r = http.call("POST", "$haUrl/api/template", token, "application/json", body)
        if (r.code != 200) throw WeatherException("Home Assistant answered HTTP ${r.code} to the template", r.code)
        return r.body
    }

    /** All weather entities, sorted by entity id. */
    fun entities(token: String): List<WeatherEntity> {
        val text = template(token, ENTITIES_TEMPLATE)
        val arr = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: throw WeatherException("Unexpected weather entity list from Home Assistant")
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            WeatherEntity(id, o.str("name") ?: id, (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }

    /** Condition, temperature and unit of [entityId], or null when it is missing/unavailable. */
    fun current(token: String, entityId: String): WeatherNow? {
        require(isWeatherEntityId(entityId)) { "not a weather entity id: $entityId" }
        val o = runCatching { json.parseToJsonElement(template(token, currentTemplate(entityId))) as? JsonObject }
            .getOrNull() ?: throw WeatherException("Unexpected weather state from Home Assistant")
        val condition = o.str("condition") ?: return null
        if (condition in UNUSABLE_STATES) return null
        return WeatherNow(
            entityId = entityId,
            condition = condition,
            temperature = o.num("temperature"),
            unit = o.str("unit").orEmpty(),
            isNight = (o["night"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /**
     * Today's (high, low) from the daily forecast, else the twice-daily one; (null, null) when the
     * entity offers neither.
     */
    fun todayHighLow(token: String, entityId: String, today: LocalDate, zone: ZoneId): Pair<Double?, Double?> {
        for (type in listOf("daily", "twice_daily")) {
            val forecast = forecast(token, entityId, type) ?: continue
            val result = WeatherForecasts.todayHighLow(forecast, type, today, zone)
            if (result.first != null || result.second != null) return result
        }
        return null to null
    }

    /** The forecast list, or null when the entity doesn't support [type] (HA answers 400). */
    private fun forecast(token: String, entityId: String, type: String): List<JsonObject>? {
        val body = buildJsonObject { put("entity_id", entityId); put("type", type) }.toString()
        val r: HaResponse = http.call(
            "POST", "$haUrl/api/services/weather/get_forecasts?return_response", token, "application/json", body,
        )
        if (r.code == 400) return null
        if (r.code != 200) throw WeatherException("Home Assistant answered HTTP ${r.code} to get_forecasts", r.code)
        val o = runCatching { json.parseToJsonElement(r.body) as? JsonObject }.getOrNull() ?: return null
        val response = o["service_response"] as? JsonObject ?: return null
        val forEntity = response[entityId] as? JsonObject ?: return null
        return (forEntity["forecast"] as? JsonArray)?.mapNotNull { it as? JsonObject }
    }

    companion object {
        private val UNUSABLE_STATES = setOf("unavailable", "unknown", "")
        private val ENTITY_ID = Regex("^weather\\.[a-z0-9_]+$")

        fun isWeatherEntityId(id: String) = ENTITY_ID.matches(id)

        /** The entity Auto picks: the first available one, else the first. */
        fun autoPick(entities: List<WeatherEntity>): WeatherEntity? =
            entities.firstOrNull { it.available } ?: entities.firstOrNull()

        internal const val ENTITIES_TEMPLATE =
            "{% set ns = namespace(l=[]) %}" +
                "{% for s in states.weather | sort(attribute='entity_id') %}" +
                "{% set ns.l = ns.l + [{'id': s.entity_id, 'name': s.name, " +
                "'ok': s.state not in ['unavailable', 'unknown']}] %}" +
                "{% endfor %}{{ ns.l | tojson }}"

        /** [entityId] must pass [isWeatherEntityId]: it is pasted into the template. */
        internal fun currentTemplate(entityId: String) =
            "{% set w = states['$entityId'] %}" +
                "{{ {'condition': w.state if w else none, " +
                "'temperature': w.attributes.temperature if w else none, " +
                "'unit': w.attributes.temperature_unit if w else none, " +
                "'night': is_state('sun.sun', 'below_horizon')} | tojson }}"
    }
}
