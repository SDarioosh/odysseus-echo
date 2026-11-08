package com.msp1974.vacompanion.odysseus.home.weather

// Odysseus fork: reads the weather for the clock page from Home Assistant through HaApi
// (HaClient, the app's one HA client). Read-only:
//   template                                weather entities; current conditions
//   weather.get_forecasts (with response)   today's high and low
// Unit-tested with a fake HaApi (WeatherClientTest).

import com.msp1974.vacompanion.odysseus.ha.HaApi
import com.msp1974.vacompanion.odysseus.ha.HaException
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

class WeatherClient(private val ha: HaApi) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    /** All weather entities, sorted by entity id. */
    suspend fun entities(): List<WeatherEntity> {
        val text = ha.template(ENTITIES_TEMPLATE)
        val arr = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: throw HaException("Unexpected weather entity list from Home Assistant")
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            WeatherEntity(id, o.str("name") ?: id, (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }

    /** Condition, temperature and unit of [entityId], or null when it is missing/unavailable. */
    suspend fun current(entityId: String): WeatherNow? {
        require(isWeatherEntityId(entityId)) { "not a weather entity id: $entityId" }
        val text = ha.template(currentTemplate(entityId))
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }
            .getOrNull() ?: throw HaException("Unexpected weather state from Home Assistant")
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
    suspend fun todayHighLow(entityId: String, today: LocalDate, zone: ZoneId): Pair<Double?, Double?> {
        for (type in listOf("daily", "twice_daily")) {
            val forecast = forecast(entityId, type) ?: continue
            val result = WeatherForecasts.todayHighLow(forecast, type, today, zone)
            if (result.first != null || result.second != null) return result
        }
        return null to null
    }

    /**
     * The forecast list, or null when the entity doesn't support [type] (REST answers 400, the
     * websocket an error with code 0) or the answer has none. Other errors (a refused sign-in,
     * HTTP 5xx) are thrown.
     */
    private suspend fun forecast(entityId: String, type: String): List<JsonObject>? {
        val data = buildJsonObject { put("entity_id", entityId); put("type", type) }
        val response = try {
            ha.call("weather", "get_forecasts", data, returnResponse = true) as? JsonObject
        } catch (e: HaException) {
            if (e.code != 400 && e.code != 0) throw e
            null
        } ?: return null
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
