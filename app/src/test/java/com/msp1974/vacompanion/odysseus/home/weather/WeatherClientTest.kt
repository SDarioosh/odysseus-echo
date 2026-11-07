package com.msp1974.vacompanion.odysseus.home.weather

import com.msp1974.vacompanion.odysseus.ha.HaHttp
import com.msp1974.vacompanion.odysseus.ha.HaResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeatherClientTest {

    private data class Call(val method: String, val path: String, val bearer: String?, val body: String?)

    /** Answers by "METHOD path" and, for the template, by a marker in the template text. */
    private class FakeHa(val answer: (Call) -> HaResponse) : HaHttp {
        val calls = mutableListOf<Call>()
        override fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse {
            val c = Call(method, url.removePrefix(BASE), bearer, body)
            calls += c
            return answer(c)
        }
    }

    companion object {
        const val BASE = "http://ha.local:8123"
        val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
        val TODAY: LocalDate = LocalDate.of(2026, 11, 5)
    }

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun listsEntitiesAndAutoPicksTheFirstAvailable() {
        val fake = FakeHa {
            HaResponse(200, """[{"id":"weather.a","name":"A","ok":false},{"id":"weather.home","name":"Home","ok":true}]""")
        }
        val list = WeatherClient(BASE, fake).entities("T")
        assertEquals(listOf(WeatherEntity("weather.a", "A", false), WeatherEntity("weather.home", "Home", true)), list)
        assertEquals("weather.home", WeatherClient.autoPick(list)?.entityId)
        assertEquals("POST", fake.calls[0].method)
        assertEquals("/api/template", fake.calls[0].path)
        assertEquals("T", fake.calls[0].bearer)
    }

    @Test
    fun autoPickFallsBackToTheFirstAndToNone() {
        assertEquals("weather.a", WeatherClient.autoPick(listOf(WeatherEntity("weather.a", "A", false)))?.entityId)
        assertNull(WeatherClient.autoPick(emptyList()))
    }

    @Test
    fun readsTheCurrentConditions() {
        val fake = FakeHa {
            HaResponse(200, """{"condition":"partlycloudy","temperature":12.4,"unit":"°C","night":true}""")
        }
        val now = WeatherClient(BASE, fake).current("T", "weather.home")!!
        assertEquals(WeatherNow("weather.home", "partlycloudy", 12.4, "°C", true), now)
        assertTrue(fake.calls[0].body!!.contains("states['weather.home']"))
    }

    @Test
    fun unavailableOrMissingEntityGivesNothing() {
        for (body in listOf("""{"condition":"unavailable"}""", """{"condition":null}""")) {
            assertNull(WeatherClient(BASE, FakeHa { HaResponse(200, body) }).current("T", "weather.home"))
        }
    }

    @Test
    fun refusesAnEntityIdThatIsNotAWeatherEntity() {
        assertFalse(WeatherClient.isWeatherEntityId("weather.x' %}{{ states"))
        assertFalse(WeatherClient.isWeatherEntityId("sensor.temp"))
        assertTrue(WeatherClient.isWeatherEntityId("weather.forecast_home"))
        try {
            WeatherClient(BASE, FakeHa { HaResponse(200, "{}") }).current("T", "weather.x']"); fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun httpErrorIsAWeatherException() {
        try {
            WeatherClient(BASE, FakeHa { HaResponse(401, "") }).entities("T"); fail()
        } catch (e: WeatherException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun highLowFromTheDailyForecast() {
        val fake = FakeHa {
            HaResponse(200, """{"changed_states":[],"service_response":{"weather.home":{"forecast":[
                {"datetime":"2026-11-05T11:00:00+00:00","condition":"rainy","temperature":14.0,"templow":7.5},
                {"datetime":"2026-11-06T11:00:00+00:00","condition":"sunny","temperature":16.0,"templow":9.0}]}}}""")
        }
        assertEquals(14.0 to 7.5, WeatherClient(BASE, fake).todayHighLow("T", "weather.home", TODAY, ZONE))
        assertEquals("/api/services/weather/get_forecasts?return_response", fake.calls[0].path)
        assertTrue(fake.calls[0].body!!.contains("\"type\":\"daily\""))
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun fallsBackToTwiceDailyWhenDailyIsNotSupported() {
        val fake = FakeHa { c ->
            if (c.body!!.contains("\"daily\"")) HaResponse(400, """{"message":"Weather entity does not support 'daily'"}""")
            else HaResponse(200, """{"service_response":{"weather.home":{"forecast":[
                {"datetime":"2026-11-05T06:00:00+00:00","is_daytime":true,"temperature":13.0},
                {"datetime":"2026-11-05T18:00:00+00:00","is_daytime":false,"temperature":6.0},
                {"datetime":"2026-11-06T06:00:00+00:00","is_daytime":true,"temperature":20.0}]}}}""")
        }
        assertEquals(13.0 to 6.0, WeatherClient(BASE, fake).todayHighLow("T", "weather.home", TODAY, ZONE))
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun noForecastAtAllGivesNoHighLow() {
        val fake = FakeHa { HaResponse(400, "") }
        assertEquals(null to null, WeatherClient(BASE, fake).todayHighLow("T", "weather.home", TODAY, ZONE))
    }

    @Test
    fun dailyStartingTomorrowUsesTheFirstEntry() {
        val f = listOf(obj("""{"datetime":"2026-11-06T11:00:00+00:00","temperature":16.0,"templow":9.0}"""))
        assertEquals(16.0 to 9.0, WeatherForecasts.todayHighLow(f, "daily", TODAY, ZONE))
    }

    @Test
    fun dailyDateIsTakenInTheLocalZone() {
        // 23:30 UTC on the 4th is already the 5th in Amsterdam.
        val f = listOf(
            obj("""{"datetime":"2026-11-03T23:30:00+00:00","temperature":1.0,"templow":0.0}"""),
            obj("""{"datetime":"2026-11-04T23:30:00+00:00","temperature":11.0,"templow":4.0}"""),
        )
        assertEquals(11.0 to 4.0, WeatherForecasts.todayHighLow(f, "daily", TODAY, ZONE))
    }

    @Test
    fun dailyWithoutTemplowGivesOnlyAHigh() {
        val f = listOf(obj("""{"datetime":"2026-11-05","temperature":14.0}"""))
        assertEquals(14.0 to null, WeatherForecasts.todayHighLow(f, "daily", TODAY, ZONE))
    }

    @Test
    fun iconsMatchHomeAssistant() {
        assertEquals(WeatherIcon.SUNNY, WeatherForecasts.icon("sunny", false))
        assertEquals(WeatherIcon.NIGHT, WeatherForecasts.icon("sunny", true))
        assertEquals(WeatherIcon.NIGHT, WeatherForecasts.icon("clear-night", false))
        assertEquals(WeatherIcon.PARTLY_CLOUDY, WeatherForecasts.icon("partlycloudy", false))
        assertEquals(WeatherIcon.NIGHT_PARTLY_CLOUDY, WeatherForecasts.icon("partlycloudy", true))
        assertEquals(WeatherIcon.CLOUDY, WeatherForecasts.icon("cloudy", true))
        assertEquals(WeatherIcon.RAINY, WeatherForecasts.icon("rainy", false))
        assertEquals(WeatherIcon.POURING, WeatherForecasts.icon("pouring", false))
        assertEquals(WeatherIcon.LIGHTNING, WeatherForecasts.icon("lightning", false))
        assertEquals(WeatherIcon.LIGHTNING_RAINY, WeatherForecasts.icon("lightning-rainy", false))
        assertEquals(WeatherIcon.SNOWY, WeatherForecasts.icon("snowy", false))
        assertEquals(WeatherIcon.SNOWY_RAINY, WeatherForecasts.icon("snowy-rainy", false))
        assertEquals(WeatherIcon.HAIL, WeatherForecasts.icon("hail", false))
        assertEquals(WeatherIcon.FOG, WeatherForecasts.icon("fog", false))
        assertEquals(WeatherIcon.WINDY, WeatherForecasts.icon("windy", false))
        assertEquals(WeatherIcon.WINDY_VARIANT, WeatherForecasts.icon("windy-variant", false))
        assertEquals(WeatherIcon.EXCEPTIONAL, WeatherForecasts.icon("exceptional", false))
        assertEquals(WeatherIcon.EXCEPTIONAL, WeatherForecasts.icon("something-new", false))
    }
}
