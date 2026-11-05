package com.msp1974.vacompanion.odysseus.home.weather

// Odysseus fork: plain rules for the weather badge, unit-tested (WeatherForecastsTest):
// which forecast entries are "today", and which icon a Home Assistant condition gets (the same
// icons as Home Assistant's own weather card, including its night variants).

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

enum class WeatherIcon {
    SUNNY, NIGHT, PARTLY_CLOUDY, NIGHT_PARTLY_CLOUDY, CLOUDY, RAINY, POURING, LIGHTNING,
    LIGHTNING_RAINY, SNOWY, SNOWY_RAINY, HAIL, FOG, WINDY, WINDY_VARIANT, EXCEPTIONAL,
}

object WeatherForecasts {

    /** Home Assistant's condition → icon, as in its frontend (src/data/weather.ts). */
    fun icon(condition: String, isNight: Boolean): WeatherIcon = when (condition) {
        "sunny" -> if (isNight) WeatherIcon.NIGHT else WeatherIcon.SUNNY
        "clear-night" -> WeatherIcon.NIGHT
        "partlycloudy" -> if (isNight) WeatherIcon.NIGHT_PARTLY_CLOUDY else WeatherIcon.PARTLY_CLOUDY
        "cloudy" -> WeatherIcon.CLOUDY
        "rainy" -> WeatherIcon.RAINY
        "pouring" -> WeatherIcon.POURING
        "lightning" -> WeatherIcon.LIGHTNING
        "lightning-rainy" -> WeatherIcon.LIGHTNING_RAINY
        "snowy" -> WeatherIcon.SNOWY
        "snowy-rainy" -> WeatherIcon.SNOWY_RAINY
        "hail" -> WeatherIcon.HAIL
        "fog" -> WeatherIcon.FOG
        "windy" -> WeatherIcon.WINDY
        "windy-variant" -> WeatherIcon.WINDY_VARIANT
        else -> WeatherIcon.EXCEPTIONAL
    }

    /**
     * (high, low) for [today] in [zone].
     * - daily: the entry dated today (else the first entry, as forecasts often start tomorrow
     *   late in the evening): temperature = high, templow = low.
     * - twice_daily: today's entries; high = warmest temperature, low = the night's temperature
     *   or else the lowest templow.
     */
    fun todayHighLow(forecast: List<JsonObject>, type: String, today: LocalDate, zone: ZoneId): Pair<Double?, Double?> {
        if (forecast.isEmpty()) return null to null
        val todays = forecast.filter { localDate(it, zone) == today }
        return when (type) {
            "daily" -> {
                val e = todays.firstOrNull() ?: forecast.first()
                e.num("temperature") to e.num("templow")
            }
            else -> {
                val entries = todays.ifEmpty { forecast.take(2) }
                val high = entries.mapNotNull { it.num("temperature") }.maxOrNull()
                val nightTemps = entries.filter { it.bool("is_daytime") == false }.mapNotNull { it.num("temperature") }
                val low = (nightTemps + entries.mapNotNull { it.num("templow") }).minOrNull()
                high to low
            }
        }
    }

    private fun localDate(e: JsonObject, zone: ZoneId): LocalDate? {
        val text = (e["datetime"] as? JsonPrimitive)?.contentOrNull ?: return null
        return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }

    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
