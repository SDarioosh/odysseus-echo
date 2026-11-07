package com.msp1974.vacompanion.odysseus.ha

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

class HaEntitiesTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val added = obj(
        """{"a":{
            "media_player.kitchen":{"s":"playing","a":{"volume_level":0.4,"media_title":"Song A","friendly_name":"Kitchen"},
                "c":"01JC","lc":1762500000.5},
            "media_player.office":{"s":"idle","a":{},"c":{"id":"x"},"lc":1762500000.0,"lu":1762500100.25}}}""",
    )

    @Test
    fun addsEntitiesWithLastUpdatedFromLuElseLc() {
        val m = HaEntities.apply(emptyMap(), added)
        assertEquals(setOf("media_player.kitchen", "media_player.office"), m.keys)
        val k = m.getValue("media_player.kitchen")
        assertEquals("playing", k.state)
        assertEquals(1762500000500L, k.lastUpdatedMs)
        assertEquals(JsonPrimitive("Song A"), k.attributes["media_title"])
        assertEquals(1762500100250L, m.getValue("media_player.office").lastUpdatedMs)
    }

    @Test
    fun changesSetAndDropAttributesAndKeepTheRest() {
        val m0 = HaEntities.apply(emptyMap(), added)
        val m1 = HaEntities.apply(
            m0,
            obj("""{"c":{"media_player.kitchen":{"+":{"a":{"volume_level":0.6,"media_artist":"B"},"lu":1762500200.0},
                "-":{"a":["media_title"]}}}}"""),
        )
        val k = m1.getValue("media_player.kitchen")
        assertEquals("playing", k.state)
        assertEquals(JsonPrimitive(0.6), k.attributes["volume_level"])
        assertEquals(JsonPrimitive("B"), k.attributes["media_artist"])
        assertNull(k.attributes["media_title"])
        assertEquals(JsonPrimitive("Kitchen"), k.attributes["friendly_name"])
        assertEquals(1762500200000L, k.lastUpdatedMs)
        assertEquals(m0["media_player.office"], m1["media_player.office"])
        // the input map isn't changed
        assertEquals(JsonPrimitive("Song A"), m0.getValue("media_player.kitchen").attributes["media_title"])
    }

    @Test
    fun stateChangeUsesLc() {
        val m = HaEntities.apply(
            HaEntities.apply(emptyMap(), added),
            obj("""{"c":{"media_player.kitchen":{"+":{"s":"paused","c":"01JD","lc":1762500300.0}}}}"""),
        )
        assertEquals("paused", m.getValue("media_player.kitchen").state)
        assertEquals(1762500300000L, m.getValue("media_player.kitchen").lastUpdatedMs)
    }

    @Test
    fun removesAndIgnoresChangesToUnknownEntities() {
        val m = HaEntities.apply(
            HaEntities.apply(emptyMap(), added),
            obj("""{"c":{"media_player.ghost":{"+":{"s":"on"}}},"r":["media_player.office"]}"""),
        )
        assertEquals(setOf("media_player.kitchen"), m.keys)
    }

    @Test
    fun readsRestStates() {
        val list = HaEntities.fromRestList(
            """[{"entity_id":"media_player.kitchen","state":"paused","attributes":{"volume_level":0.3},
                 "last_changed":"2026-11-07T10:00:00+00:00","last_updated":"2026-11-07T10:00:01.250000+00:00"},
                {"entity_id":"sensor.no_state"},
                {"entity_id":"light.x","state":"on","attributes":{},"last_updated":"bad"}]""",
        )
        assertEquals(2, list.size)
        assertEquals("paused", list[0].state)
        assertEquals(1794045601250L, list[0].lastUpdatedMs)
        assertEquals(0L, list[1].lastUpdatedMs)
        assertTrue(list[1].attributes.isEmpty())
        try {
            HaEntities.fromRestList("""{"message":"no"}"""); fail()
        } catch (e: HaException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }
}
