package com.msp1974.vacompanion.odysseus.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HaRestApiTest {
    private fun api(http: FakeHaHttp, auth: FakeHaAuth = FakeHaAuth(), bytes: HaBytesHttp = HaBytesHttp { _, _, _ -> HaBytes(404, ByteArray(0)) }) =
        HaRestApi(auth, http, bytes, Dispatchers.Unconfined)

    private val data = buildJsonObject { put("entity_id", "media_player.kitchen") }

    @Test
    fun serviceCallWithResponse() = runBlocking {
        val http = FakeHaHttp { HaResponse(200, """{"changed_states":[],"service_response":{"media_player.kitchen":{"items":[]}}}""") }
        val r = api(http).call("music_assistant", "get_queue", data, returnResponse = true)
        val c = http.calls.single()
        assertEquals("POST", c.method)
        assertEquals("/api/services/music_assistant/get_queue?return_response", c.path)
        assertEquals("T1", c.bearer)
        assertEquals(data, Json.parseToJsonElement(c.body!!))
        assertTrue((r as JsonObject).containsKey("media_player.kitchen"))
    }

    @Test
    fun serviceCallWithoutResponseHasNoQuery() = runBlocking {
        val http = FakeHaHttp { HaResponse(200, "[]") }
        assertNull(api(http).call("media_player", "media_pause", data))
        assertEquals("/api/services/media_player/media_pause", http.calls.single().path)
    }

    @Test
    fun emptyResponseIsJsonNull() = runBlocking {
        val http = FakeHaHttp { HaResponse(200, """{"changed_states":[]}""") }
        assertEquals(JsonNull, api(http).call("x", "y", data, returnResponse = true))
    }

    @Test
    fun badServiceNamesAreRefused() = runBlocking {
        val http = FakeHaHttp { HaResponse(200, "[]") }
        try {
            api(http).call("media_player", "../config", data); fail()
        } catch (e: IllegalArgumentException) {
        }
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun a401IsRetriedOnceWithARefreshedToken() = runBlocking {
        val http = FakeHaHttp { c -> if (c.bearer == "T1") HaResponse(401, "") else HaResponse(200, "ok") }
        val auth = FakeHaAuth()
        assertEquals("ok", api(http, auth).template("{{ 1 }}"))
        assertEquals(listOf("T1", "T2"), http.calls.map { it.bearer })
        assertEquals(listOf(false, true), auth.refreshes)
    }

    @Test
    fun repeated401sStopAfterOneRetry() = runBlocking {
        val http = FakeHaHttp { HaResponse(401, "") }
        try {
            api(http).template("{{ 1 }}"); fail()
        } catch (e: HaException) {
            assertEquals(401, e.code)
        }
        assertEquals(2, http.calls.size)
    }

    @Test
    fun errorsCarryTheCodeAndMessage() = runBlocking {
        val http = FakeHaHttp { HaResponse(400, """{"message":"Service does not support responses"}""") }
        try {
            api(http).call("light", "turn_on", data, returnResponse = true); fail()
        } catch (e: HaException) {
            assertEquals(400, e.code)
            assertTrue(e.message!!.contains("does not support responses"))
            assertTrue(!e.message!!.contains("?return_response"))
        }
        assertEquals(1, http.calls.size)
    }

    @Test
    fun templateSendsTheTemplate() = runBlocking {
        val http = FakeHaHttp { HaResponse(200, "[1,2]") }
        assertEquals("[1,2]", api(http).template("{{ [1,2] }}"))
        val c = http.calls.single()
        assertEquals("/api/template", c.path)
        assertEquals("{{ [1,2] }}", (Json.parseToJsonElement(c.body!!).jsonObject["template"] as JsonPrimitive).content)
    }

    @Test
    fun statesAreFiltered() = runBlocking {
        val http = FakeHaHttp {
            HaResponse(200, """[{"entity_id":"media_player.a","state":"idle","attributes":{}},
                {"entity_id":"light.b","state":"on","attributes":{}}]""")
        }
        val list = api(http).states { it.entityId.startsWith("media_player.") }
        assertEquals(listOf("media_player.a"), list.map { it.entityId })
        assertEquals(FakeCall("GET", "/api/states", "T1", null), http.calls.single())
    }

    @Test
    fun bytesFromAHaPathOnly() = runBlocking {
        val seen = mutableListOf<String>()
        val bytes = HaBytesHttp { url, bearer, max ->
            seen += "$url $bearer $max"
            HaBytes(200, byteArrayOf(1, 2, 3))
        }
        val a = api(FakeHaHttp { HaResponse(500, "") }, bytes = bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), a.bytes("/api/media_player_proxy/media_player.kitchen?token=x"))
        assertEquals(listOf("http://ha.local:8123/api/media_player_proxy/media_player.kitchen?token=x T1 ${HaRestApi.MAX_BYTES}"), seen)
        for (bad in listOf("http://evil/x", "//evil/x", "api/x", "/api/../../x")) {
            try {
                a.bytes(bad); fail(bad)
            } catch (e: HaException) {
            }
        }
        assertEquals(1, seen.size)
    }
}
