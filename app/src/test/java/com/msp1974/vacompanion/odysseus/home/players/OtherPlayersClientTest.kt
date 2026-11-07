package com.msp1974.vacompanion.odysseus.home.players

import com.msp1974.vacompanion.odysseus.HaHttp
import com.msp1974.vacompanion.odysseus.HaResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OtherPlayersClientTest {
    private data class Call(val method: String, val url: String, val bearer: String?, val body: String?)

    private class FakeHa(val answer: (Call) -> HaResponse) : HaHttp {
        val calls = mutableListOf<Call>()
        override fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse {
            val c = Call(method, url, bearer, body)
            calls += c
            return answer(c)
        }
    }

    private val base = "http://ha.local:8123"

    @Test
    fun readsTheSnapshotThroughTheTemplate() {
        val fake = FakeHa {
            HaResponse(200, """{"now": 5.0, "players": [{"id": "media_player.kitchen", "name": "Kitchen", "state": "playing", "title": "T"}]}""")
        }
        val s = OtherPlayersClient(base, fake).snapshot("TOKEN")
        assertEquals(listOf("media_player.kitchen"), s.players.map { it.entityId })
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertEquals("$base/api/template", call.url)
        assertEquals("TOKEN", call.bearer)
        val template = Json.parseToJsonElement(call.body!!).jsonObject["template"]!!.jsonPrimitive.content
        assertTrue(template.contains("states.media_player"))
        assertTrue(template.contains("area_name(s.entity_id)"))
    }

    @Test
    fun aRefusedOrOddAnswerIsAnError() {
        for (r in listOf(HaResponse(401, ""), HaResponse(200, "not json"), HaResponse(200, "[]"))) {
            try {
                OtherPlayersClient(base, FakeHa { r }).snapshot("T")
                fail("expected an error for $r")
            } catch (_: OtherPlayersException) {
            }
        }
    }

    @Test
    fun sendsTheServiceCall() {
        val fake = FakeHa { HaResponse(200, "[]") }
        OtherPlayersClient(base, fake).send("T", OtherPlayersRules.serviceCall("media_player.kitchen", "next")!!)
        val call = fake.calls.single()
        assertEquals("$base/api/services/media_player/media_next_track", call.url)
        assertEquals("media_player.kitchen", Json.parseToJsonElement(call.body!!).jsonObject["entity_id"]!!.jsonPrimitive.content)
        try {
            OtherPlayersClient(base, FakeHa { HaResponse(400, "") }).send("T", OtherPlayersRules.serviceCall("media_player.kitchen", "play")!!)
            fail("expected an error")
        } catch (e: OtherPlayersException) {
            assertEquals(400, e.code)
        }
    }
}
