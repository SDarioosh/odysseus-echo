package com.msp1974.vacompanion.odysseus.music

import com.msp1974.vacompanion.odysseus.ha.HaState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Recorded-shape Music Assistant answers (fictional names): app/src/test/resources/odysseus/music/. */
object MusicFixtures {
    fun text(name: String): String {
        val url = MusicFixtures::class.java.classLoader?.getResource("odysseus/music/$name")
            ?: error("missing fixture odysseus/music/$name")
        return url.readText()
    }

    fun json(name: String): JsonElement = Json.parseToJsonElement(text(name))

    fun state(entityId: String, state: String = "playing", attributes: JsonObject = JsonObject(emptyMap()), updatedMs: Long = 1_000L) =
        HaState(entityId, state, attributes, updatedMs)

    fun named(entityId: String, name: String, state: String = "idle") =
        state(entityId, state, JsonObject(mapOf("friendly_name" to JsonPrimitive(name))))
}
