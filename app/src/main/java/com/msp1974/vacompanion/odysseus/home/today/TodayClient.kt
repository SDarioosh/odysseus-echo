package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: reads the Today page's data from Odysseus. Read-only, with the voice token the
// device already holds (OdysseusLink):
//   GET <voice base>/today   ->  contract C1 (docs/odysseus/plans/2026-11-today-updates-announce-record.md)
// Parsing is lenient: unknown keys are ignored, a missing or null section is empty, and one
// malformed item is skipped rather than failing the whole page. HTTP goes through TodayHttp so it
// is unit-tested with a fake (TodayClientTest).

import com.msp1974.vacompanion.odysseus.HaResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class TodayFocus(
    val ref: String = "",
    val title: String = "",
    val why: String? = null,
)

@Serializable
data class TodayEvent(
    val uid: String = "",
    val title: String = "",
    /** ISO-8601 with offset; a date only for some all-day events. */
    val start: String = "",
    val end: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String? = null,
)

@Serializable
data class TodayTodo(
    val ref: String = "",
    val title: String = "",
    /** "2026-11-06" (or a full ISO time); null for undated pinned items. */
    val due: String? = null,
    val list: String? = null,
)

@Serializable
data class TodayActionItem(
    val id: String = "",
    val title: String = "",
    val source: String? = null,
)

@Serializable
data class TodayCounts(
    @SerialName("todos_open") val todosOpen: Int? = null,
    @SerialName("action_items_open") val actionItemsOpen: Int? = null,
)

/** Contract C1. Every section is empty rather than missing. */
data class TodayData(
    val date: String = "",
    val generated: String? = null,
    val headline: String? = null,
    val focus: List<TodayFocus> = emptyList(),
    val events: List<TodayEvent> = emptyList(),
    val todos: List<TodayTodo> = emptyList(),
    val actionItems: List<TodayActionItem> = emptyList(),
    val counts: TodayCounts = TodayCounts(),
)

class TodayException(message: String, val code: Int = 0) : Exception(message)

/** One GET to the hub, path relative to the voice base URL. Throws on network errors. */
fun interface TodayHttp {
    fun get(path: String): HaResponse
}

class TodayClient(private val http: TodayHttp) {

    /** GET /today. Throws [TodayException] on any answer but a 200 with a JSON object. */
    fun fetch(): TodayData {
        val r = http.get(PATH)
        if (r.code != 200) throw TodayException(describe(r.code), r.code)
        return parse(r.body) ?: throw TodayException("Unexpected answer from Odysseus")
    }

    companion object {
        const val PATH = "/today"

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

        fun describe(code: Int): String = when (code) {
            401, 403 -> "Odysseus refused the token ($code): link the device again"
            404 -> "This Odysseus has no Today route yet (404)"
            else -> "Odysseus answered HTTP $code"
        }

        /** Contract C1 from [body], or null when it isn't a JSON object. */
        fun parse(body: String): TodayData? {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            fun str(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            return TodayData(
                date = str("date").orEmpty(),
                generated = str("generated"),
                headline = str("headline")?.trim(),
                focus = list(root, "focus", TodayFocus.serializer()).filter { it.title.isNotBlank() },
                events = list(root, "events", TodayEvent.serializer()).filter { it.title.isNotBlank() },
                todos = list(root, "todos", TodayTodo.serializer()).filter { it.title.isNotBlank() },
                actionItems = list(root, "action_items", TodayActionItem.serializer()).filter { it.title.isNotBlank() },
                counts = (root["counts"] as? JsonObject)
                    ?.let { runCatching { json.decodeFromJsonElement(TodayCounts.serializer(), it) }.getOrNull() }
                    ?: TodayCounts(),
            )
        }

        /** The items of [key] that decode; anything else (missing, null, not a list) is empty. */
        private fun <T> list(root: JsonObject, key: String, serializer: KSerializer<T>): List<T> {
            val arr = root[key] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                (el as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() }
            }
        }
    }
}
