package com.msp1974.vacompanion.odysseus.music

import com.msp1974.vacompanion.odysseus.ha.HaApi
import com.msp1974.vacompanion.odysseus.ha.HaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Collections

/** An HaApi that records what it is asked and answers from a script. */
class FakeHaApi : HaApi {
    data class Call(val domain: String, val service: String, val data: JsonObject, val returnResponse: Boolean)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
    val templates: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val watched: MutableList<Set<String>> = Collections.synchronizedList(mutableListOf())

    /** The template's answer, or what it throws. */
    @Volatile var templateAnswer: () -> String = { "[]" }
    @Volatile var allStates: List<HaState> = emptyList()

    /** "domain.service" → the response, or what it throws. */
    val answers: MutableMap<String, () -> JsonElement?> = Collections.synchronizedMap(mutableMapOf())

    /** What watch() reports (filtered to the ids asked for, as subscribe_entities would). */
    val live = MutableStateFlow<Map<String, HaState>>(emptyMap())

    /** When false, watch() passes on every entity (to report entities it wasn't asked about). */
    @Volatile var filterWatch = true

    override suspend fun template(template: String): String {
        templates += template
        return templateAnswer()
    }

    override suspend fun call(domain: String, service: String, data: JsonObject, returnResponse: Boolean): JsonElement? {
        calls += Call(domain, service, data, returnResponse)
        return answers["$domain.$service"]?.invoke()
    }

    override suspend fun states(filter: (HaState) -> Boolean): List<HaState> = allStates.filter(filter)

    override fun watch(entityIds: Set<String>): Flow<Map<String, HaState>> {
        watched += entityIds
        return live.map { m -> if (filterWatch) m.filterKeys { it in entityIds } else m }
    }

    override suspend fun bytes(path: String): ByteArray = ByteArray(0)
}
