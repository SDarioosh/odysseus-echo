package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: Home Assistant entity states into HaState, from REST (GET /api/states) and
// from the websocket's compressed subscribe_entities events. Pure (HaEntitiesTest).
//
// A subscribe_entities event:
//   {"a": {eid: {"s": state, "a": {attributes}, "c": context, "lc": 1730000000.1, "lu": …}},
//    "c": {eid: {"+": {"s"?, "a"? (attributes to set), "c"?, "lc"?, "lu"?},
//                "-": {"a": [attribute names to drop]}}},
//    "r": [eid, …]}
// lc/lu are epoch seconds; "lu" is left out when it equals "lc". The first event after
// subscribing adds every entity ("a"); later ones change ("c") or remove ("r") them.

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.OffsetDateTime
import kotlin.math.roundToLong

object HaEntities {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.seconds(key: String): Long? =
        (this[key] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).roundToLong() }

    /** One element of GET /api/states; null when it has no entity id or state. */
    fun fromRest(o: JsonObject): HaState? {
        val id = o.str("entity_id") ?: return null
        val state = o.str("state") ?: return null
        return HaState(id, state, o.obj("attributes") ?: JsonObject(emptyMap()), isoMs(o.str("last_updated")))
    }

    /** The body of GET /api/states. Throws HaException when it isn't a JSON list. */
    fun fromRestList(body: String): List<HaState> {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: throw HaException("Unexpected state list from Home Assistant")
        return arr.mapNotNull { (it as? JsonObject)?.let(::fromRest) }
    }

    /** "2026-11-07T10:00:00.123456+00:00" → epoch ms; 0 when missing or unreadable. */
    fun isoMs(text: String?): Long =
        text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

    /** [current] after one subscribe_entities [event] (the message's "event" object). */
    fun apply(current: Map<String, HaState>, event: JsonObject): Map<String, HaState> {
        val out = LinkedHashMap(current)
        event.obj("a")?.forEach { (id, v) ->
            val o = v as? JsonObject ?: return@forEach
            val state = o.str("s") ?: return@forEach
            out[id] = HaState(id, state, o.obj("a") ?: JsonObject(emptyMap()), o.seconds("lu") ?: o.seconds("lc") ?: 0L)
        }
        event.obj("c")?.forEach { (id, v) ->
            val o = v as? JsonObject ?: return@forEach
            val old = out[id] ?: return@forEach // a change to an entity we never saw added
            out[id] = change(old, o.obj("+"), o.obj("-"))
        }
        (event["r"] as? JsonArray)?.forEach { el ->
            (el as? JsonPrimitive)?.contentOrNull?.let { out.remove(it) }
        }
        return out
    }

    private fun change(old: HaState, plus: JsonObject?, minus: JsonObject?): HaState {
        var attributes: Map<String, JsonElement> = old.attributes
        plus?.obj("a")?.let { attributes = attributes + it }
        (minus?.get("a") as? JsonArray)?.let { names ->
            attributes = attributes - names.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        }
        return old.copy(
            state = plus?.str("s") ?: old.state,
            attributes = if (attributes === old.attributes) old.attributes else JsonObject(attributes),
            lastUpdatedMs = plus?.seconds("lu") ?: plus?.seconds("lc") ?: old.lastUpdatedMs,
        )
    }
}
