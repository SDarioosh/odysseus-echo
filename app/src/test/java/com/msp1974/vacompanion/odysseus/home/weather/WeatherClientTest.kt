package com.msp1974.vacompanion.odysseus.home.weather

import com.msp1974.vacompanion.odysseus.ha.HaException
import com.msp1974.vacompanion.odysseus.music.FakeHaApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
        val TODAY: LocalDate = LocalDate.of(2026, 11, 5)
    }

    private fun ha(template: String = "[]") = FakeHaApi().apply { templateAnswer = { template } }

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun listsEntitiesAndAutoPicksTheFirstAvailable() = runBlocking {
        val fake = ha("""[{"id":"weather.a","name":"A","ok":false},{"id":"weather.home","name":"Home","ok":true}]""")
        val list = WeatherClient(fake).entities()
        assertEquals(listOf(WeatherEntity("weather.a", "A", false), WeatherEntity("weather.home", "Home", true)), list)
        assertEquals("weather.home", WeatherClient.autoPick(list)?.entityId)
        assertTrue(fake.templates.single().contains("states.weather"))
    }

    @Test
    fun autoPickFallsBackToTheFirstAndToNone() {
        assertEquals("weather.a", WeatherClient.autoPick(listOf(WeatherEntity("weather.a", "A", false)))?.entityId)
        assertNull(WeatherClient.autoPick(emptyList()))
    }

    @Test
    fun readsTheCurrentConditions() = runBlocking {
        val fake = ha("""{"condition":"partlycloudy","temperature":12.4,"unit":"°C","night":true}""")
        val now = WeatherClient(fake).current("weather.home")!!
        assertEquals(WeatherNow("weather.home", "partlycloudy", 12.4, "°C", true), now)
        assertTrue(fake.templates.single().contains("states['weather.home']"))
    }

    @Test
    fun unavailableOrMissingEntityGivesNothing() = runBlocking {
        for (body in listOf("""{"condition":"unavailable"}""", """{"condition":null}""")) {
            assertNull(WeatherClient(ha(body)).current("weather.home"))
        }
    }

    @Test
    fun refusesAnEntityIdThatIsNotAWeatherEntity() = runBlocking {
        assertFalse(WeatherClient.isWeatherEntityId("weather.x' %}{{ states"))
        assertFalse(WeatherClient.isWeatherEntityId("sensor.temp"))
        assertTrue(WeatherClient.isWeatherEntityId("weather.forecast_home"))
        try {
            WeatherClient(ha("{}")).current("weather.x']"); fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun anOddAnswerIsAnHaException() = runBlocking {
        try {
            WeatherClient(ha("not json")).entities(); fail()
        } catch (_: HaException) {
        }
    }

    private val daily = """{"weather.home":{"forecast":[
        {"datetime":"2026-11-05T11:00:00+00:00","condition":"rainy","temperature":14.0,"templow":7.5},
        {"datetime":"2026-11-06T11:00:00+00:00","condition":"sunny","temperature":16.0,"templow":9.0}]}}"""

    @Test
    fun highLowFromTheDailyForecast() = runBlocking {
        val fake = ha().apply { answers["weather.get_forecasts"] = { Json.parseToJsonElement(daily) } }
        assertEquals(14.0 to 7.5, WeatherClient(fake).todayHighLow("weather.home", TODAY, ZONE))
        val call = fake.calls.single()
        assertTrue(call.returnResponse)
        assertEquals(JsonPrimitive("daily"), call.data["type"])
        assertEquals(JsonPrimitive("weather.home"), call.data["entity_id"])
    }

    @Test
    fun fallsBackToTwiceDailyWhenDailyIsNotSupported() = runBlocking {
        val twice = Json.parseToJsonElement("""{"weather.home":{"forecast":[
            {"datetime":"2026-11-05T06:00:00+00:00","is_daytime":true,"temperature":13.0},
            {"datetime":"2026-11-05T18:00:00+00:00","is_daytime":false,"temperature":6.0},
            {"datetime":"2026-11-06T06:00:00+00:00","is_daytime":true,"temperature":20.0}]}}""")
        // REST answers 400, the websocket an error with code 0: both mean "not supported".
        for (code in listOf(400, 0)) {
            val fake = ha()
            fake.answers["weather.get_forecasts"] = {
                if (fake.calls.last().data["type"] == JsonPrimitive("daily")) throw HaException("not supported", code)
                twice
            }
            assertEquals(13.0 to 6.0, WeatherClient(fake).todayHighLow("weather.home", TODAY, ZONE))
            assertEquals(2, fake.calls.size)
        }
    }

    @Test
    fun noForecastAtAllGivesNoHighLow() = runBlocking {
        val fake = ha().apply { answers["weather.get_forecasts"] = { throw HaException("bad request", 400) } }
        assertEquals(null to null, WeatherClient(fake).todayHighLow("weather.home", TODAY, ZONE))
    }

    @Test
    fun aRefusedSignInIsThrownNotRetriedAsTwiceDaily() = runBlocking {
        val fake = ha().apply { answers["weather.get_forecasts"] = { throw HaException("refused", 401) } }
        try {
            WeatherClient(fake).todayHighLow("weather.home", TODAY, ZONE); fail()
        } catch (e: HaException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, fake.calls.size)
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
