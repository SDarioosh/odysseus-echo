package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: Home Assistant's Music Assistant answers read into the Music page's models, and
// the service-call data built from them (plan contract I2). Pure, unit-tested on recorded JSON
// (HaMusicJsonTest, app/src/test/resources/odysseus/music/). HaMusicBackend does the calls.

import com.msp1974.vacompanion.odysseus.ha.HaState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLEncoder
import java.time.OffsetDateTime

/** One row of the rooms template: an MA media player as Home Assistant knows it. */
data class RoomRow(
    val entityId: String,
    val name: String,
    val area: String,
    val type: String?,
    val members: List<String>,
    val ids: List<List<String>>,
    val entry: String?,
)

/** A service to call on Home Assistant: `domain.service` with [data]. */
data class HaCall(val domain: String, val service: String, val data: JsonObject)

object HaMusicJson {
    /**
     * Every MA media player with its name, area, type, group members, device identifiers and MA
     * config entry. Fixed text, never user input (needs an admin token: /api/template).
     */
    val ROOMS_TEMPLATE = """
{% set ns = namespace(r=[]) %}
{% for e in integration_entities('music_assistant') if e.startswith('media_player.')
   and state_attr(e, 'device_class') != 'tv' %}
{% set ns.r = ns.r + [{'entity_id': e, 'name': state_attr(e, 'friendly_name'),
   'area': area_name(e) or '', 'type': state_attr(e, 'mass_player_type'),
   'members': state_attr(e, 'group_members') or [],
   'ids': (device_attr(device_id(e), 'identifiers') or []) | map('list') | list,
   'entry': config_entry_id(e)}] %}
{% endfor %}{{ ns.r | to_json }}
""".trim()

    /** Image size asked of the hub (C8). */
    const val IMAGE_SIZE = 256

    // MediaPlayerEntityFeature bits (homeassistant/components/media_player/const.py).
    const val F_PAUSE = 1
    const val F_SEEK = 2
    const val F_VOLUME_SET = 4
    const val F_VOLUME_MUTE = 8
    const val F_PREVIOUS_TRACK = 16
    const val F_NEXT_TRACK = 32
    const val F_PLAY = 16384
    const val F_SHUFFLE_SET = 32768
    const val F_REPEAT_SET = 262144
    const val F_GROUPING = 524288

    private val FEATURES = listOf(
        F_PLAY to Control.PLAY, F_PAUSE to Control.PAUSE, F_NEXT_TRACK to Control.NEXT,
        F_PREVIOUS_TRACK to Control.PREVIOUS, F_SEEK to Control.SEEK, F_SHUFFLE_SET to Control.SHUFFLE,
        F_REPEAT_SET to Control.REPEAT, F_VOLUME_SET to Control.VOLUME, F_VOLUME_MUTE to Control.MUTE,
        F_GROUPING to Control.GROUP,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- small JSON helpers ----

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull
    private fun JsonElement?.strings(): List<String> = arr()?.mapNotNull { it.str() } ?: emptyList()

    // ---- rooms ----

    /** The rooms template's output; rows without an entity id are dropped. Throws on text that isn't a JSON list. */
    fun parseRooms(text: String): List<RoomRow> {
        val list = json.parseToJsonElement(text.trim()).arr() ?: throw IllegalArgumentException("rooms: not a list")
        return list.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val id = o["entity_id"].str()?.takeIf { it.startsWith("media_player.") } ?: return@mapNotNull null
            RoomRow(
                entityId = id,
                name = o["name"].str()?.takeIf { it.isNotBlank() } ?: id.removePrefix("media_player."),
                area = o["area"].str() ?: "",
                type = o["type"].str(),
                members = o["members"].strings(),
                ids = o["ids"].arr()?.mapNotNull { pair -> pair.arr()?.mapNotNull { it.str() } } ?: emptyList(),
                entry = o["entry"].str()?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Rooms from `GET /api/states` when the template is refused (a token that isn't admin): MA
     * players have `mass_player_type`. No area, identifiers or config entry are known then.
     */
    fun roomRowsFromStates(states: List<HaState>): List<RoomRow> = states
        .filter { isMaPlayerState(it) }
        .map {
            RoomRow(
                entityId = it.entityId,
                name = it.attributes["friendly_name"].str() ?: it.entityId.removePrefix("media_player."),
                area = "",
                type = it.attributes["mass_player_type"].str(),
                members = it.attributes["group_members"].strings(),
                ids = emptyList(),
                entry = null,
            )
        }

    fun isMaPlayerState(s: HaState): Boolean =
        s.entityId.startsWith("media_player.") && s.attributes.containsKey("mass_player_type") &&
            s.attributes["device_class"].str() != "tv"

    fun isAvailable(state: HaState?): Boolean = state == null || state.state != "unavailable"

    fun candidate(row: RoomRow, state: HaState?): Candidate = Candidate(
        entityId = row.entityId,
        ids = row.ids,
        name = state?.attributes?.get("friendly_name").str() ?: row.name,
        area = row.area,
        type = row.type,
        available = isAvailable(state),
    )

    /**
     * The rooms the page lists: names and group members as live as [states] has them, this device
     * ([self]) first, then by name.
     */
    fun buildRooms(rows: List<RoomRow>, states: Map<String, HaState>, self: SelfMatch?): List<Room> = rows
        .map { row ->
            val s = states[row.entityId]
            Room(
                entityId = row.entityId,
                name = s?.attributes?.get("friendly_name").str() ?: row.name,
                area = row.area.takeIf { it.isNotBlank() },
                isThisDevice = self?.entityId == row.entityId,
                isGroup = row.type == "group",
                groupMembers = s?.attributes?.get("group_members")?.strings() ?: row.members,
                available = isAvailable(s),
            )
        }
        .sortedWith(compareBy<Room>({ !it.isThisDevice }, { it.name.lowercase() }, { it.entityId }))

    // ---- players ----

    /** A media_player's state as the neutral PlayerState. [nowMs] stands in for a missing update time. */
    fun playerState(s: HaState, nowMs: Long = s.lastUpdatedMs): PlayerState {
        val a = s.attributes
        val features = a["supported_features"].num()?.toLong() ?: 0L
        val usable = s.state != "unavailable" && s.state != "unknown"
        val updatedAt = a["media_position_updated_at"].str()?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }
        return PlayerState(
            title = a["media_title"].str(),
            artist = a["media_artist"].str(),
            album = a["media_album_name"].str(),
            artUrl = (a["entity_picture_local"].str() ?: a["entity_picture"].str())?.takeIf { it.isNotBlank() },
            playing = s.state == "playing",
            durationS = a["media_duration"].num()?.takeIf { it > 0 },
            positionS = a["media_position"].num()?.coerceAtLeast(0.0),
            positionAtMs = updatedAt ?: if (s.lastUpdatedMs > 0) s.lastUpdatedMs else nowMs,
            shuffle = a["shuffle"].bool() ?: false,
            repeat = when (a["repeat"].str()) { "one" -> Repeat.ONE; "all" -> Repeat.ALL; else -> Repeat.OFF },
            volume = a["volume_level"].num()?.toFloat()?.coerceIn(0f, 1f),
            muted = a["is_volume_muted"].bool() ?: false,
            can = if (!usable) emptySet() else FEATURES.filter { (bit, _) -> features and bit.toLong() != 0L }.map { it.second }.toSet(),
        )
    }

    // ---- items ----

    fun kindOf(mediaType: String?): MediaKind? = when (mediaType?.lowercase()) {
        "track" -> MediaKind.TRACK
        "album" -> MediaKind.ALBUM
        "artist" -> MediaKind.ARTIST
        "playlist" -> MediaKind.PLAYLIST
        "radio" -> MediaKind.RADIO
        "podcast" -> MediaKind.PODCAST
        "audiobook" -> MediaKind.AUDIOBOOK
        else -> null
    }

    fun mediaType(kind: MediaKind): String = kind.name.lowercase()

    private fun imageOf(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.str()
        is JsonObject -> e["path"].str()
        else -> null
    }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /**
     * An MA item as a MediaItem; null without a uri or a known media type. [image] turns the item's
     * own image URL into one the device may load (the hub's C8 URL), or null for the placeholder.
     */
    fun item(e: JsonElement?, image: (String) -> String?): MediaItem? {
        val o = e.obj() ?: return null
        val uri = o["uri"].str()?.takeIf { it.isNotBlank() } ?: return null
        val kind = kindOf(o["media_type"].str()) ?: return null
        val artists = o["artists"].arr()?.mapNotNull { it.obj()?.get("name").str() }?.filter { it.isNotBlank() } ?: emptyList()
        val album = o["album"].obj()?.get("name").str()
        val subtitle = when (kind) {
            MediaKind.ARTIST, MediaKind.RADIO -> ""
            MediaKind.TRACK -> listOfNotNull(artists.joinToString(", ").takeIf { it.isNotEmpty() }, album).joinToString(" · ")
            else -> artists.joinToString(", ").ifEmpty { o["owner"].str() ?: o["publisher"].str() ?: "" }
        }
        val version = o["version"].str()?.takeIf { it.isNotBlank() }
        val name = (o["name"].str() ?: uri).let { if (version != null) "$it ($version)" else it }
        return MediaItem(
            uri = uri,
            kind = kind,
            name = name,
            subtitle = subtitle,
            imageUrl = imageOf(o["image"])?.let(image),
            favorite = o["favorite"].bool() ?: false,
        )
    }

    // ---- library ----

    fun orderBy(order: LibraryOrder): String = when (order) {
        LibraryOrder.NAME -> "sort_name"
        LibraryOrder.RECENT -> "last_played_desc"
        LibraryOrder.ADDED -> "timestamp_added_desc"
        LibraryOrder.MOST_PLAYED -> "play_count_desc"
        LibraryOrder.RANDOM -> "random"
    }

    /** `favorite` is sent only when true: MA reads `false` as "only items that aren't favourites". */
    fun libraryCall(entry: String, kind: MediaKind, order: LibraryOrder, favoritesOnly: Boolean, offset: Int, limit: Int) =
        HaCall("music_assistant", "get_library", buildJsonObject {
            put("config_entry_id", entry)
            put("media_type", mediaType(kind))
            put("order_by", orderBy(order))
            if (favoritesOnly) put("favorite", true)
            put("offset", offset.coerceAtLeast(0))
            put("limit", limit.coerceIn(1, 500))
        })

    /** A page; [hasMore] while a full page came back (MA gives no total). */
    fun parseLibrary(response: JsonElement?, offset: Int, limit: Int, image: (String) -> String?): LibraryPage {
        val o = response.obj() ?: return LibraryPage(emptyList(), offset, false)
        val raw = o["items"].arr() ?: JsonArray(emptyList())
        val items = raw.mapNotNull { item(it, image) }
        val at = o["offset"].num()?.toInt() ?: offset
        return LibraryPage(items, at, raw.size >= limit && raw.isNotEmpty())
    }

    // ---- search ----

    private val SEARCH_KEYS = mapOf(
        MediaKind.ARTIST to "artists", MediaKind.ALBUM to "albums", MediaKind.TRACK to "tracks",
        MediaKind.PLAYLIST to "playlists", MediaKind.RADIO to "radio", MediaKind.AUDIOBOOK to "audiobooks",
        MediaKind.PODCAST to "podcasts",
    )

    fun searchCall(entry: String, text: String, kinds: Set<MediaKind>, limit: Int) =
        HaCall("music_assistant", "search", buildJsonObject {
            put("config_entry_id", entry)
            put("name", text)
            putJsonArray("media_type") { MediaKind.entries.filter { it in kinds }.forEach { add(JsonPrimitive(mediaType(it))) } }
            put("limit", limit.coerceIn(1, 100))
        })

    /** Results for each kind asked for, in MediaKind order (an empty list when MA found none). */
    fun parseSearch(response: JsonElement?, kinds: Set<MediaKind>, image: (String) -> String?): Map<MediaKind, List<MediaItem>> {
        val o = response.obj()
        val out = linkedMapOf<MediaKind, List<MediaItem>>()
        for (kind in MediaKind.entries) {
            if (kind !in kinds) continue
            // One row per uri: the page keys its rows by kind and uri.
            out[kind] = o?.get(SEARCH_KEYS.getValue(kind)).arr()?.mapNotNull { item(it, image) }
                ?.filter { it.kind == kind }?.distinctBy { it.uri } ?: emptyList()
        }
        return out
    }

    // ---- queue ----

    fun queueCall(entityId: String) =
        HaCall("music_assistant", "get_queue", buildJsonObject { put("entity_id", entityId) })

    /** True for the error HA gives when the player's source isn't an MA queue. */
    fun isNoQueue(e: Throwable): Boolean = e.message?.contains("No active queue", ignoreCase = true) == true

    private fun queueItem(e: JsonElement?, image: (String) -> String?): MediaItem? {
        val o = e.obj() ?: return null
        item(o["media_item"], image)?.let { return it }
        val name = o["name"].str()?.takeIf { it.isNotBlank() } ?: return null
        return MediaItem(
            uri = o["queue_item_id"].str() ?: name,
            kind = MediaKind.TRACK,
            name = name,
            subtitle = o["stream_title"].str() ?: "",
            imageUrl = imageOf(o["image"])?.let(image),
        )
    }

    /** The queue of [entityId] (the answer is keyed by entity id); null when there is none. */
    fun parseQueue(response: JsonElement?, entityId: String, image: (String) -> String?): QueueSummary? {
        val all = response.obj() ?: return null
        val q = (all[entityId] ?: all.values.singleOrNull()).obj() ?: return null
        val count = (q["items"] as? JsonPrimitive)?.intOrNull ?: q["items"].arr()?.size ?: 0
        return QueueSummary(queueItem(q["current_item"], image), queueItem(q["next_item"], image), count.coerceAtLeast(0))
    }

    // ---- actions ----

    fun enqueue(e: Enqueue): String = when (e) {
        Enqueue.PLAY -> "play"
        Enqueue.NEXT -> "next"
        Enqueue.ADD -> "add"
        Enqueue.REPLACE -> "replace"
    }

    fun playCall(item: MediaItem, roomId: String, enqueue: Enqueue, radio: Boolean) =
        HaCall("music_assistant", "play_media", buildJsonObject {
            put("entity_id", roomId)
            put("media_id", item.uri)
            put("media_type", mediaType(item.kind))
            put("enqueue", enqueue(enqueue))
            put("radio_mode", radio)
        })

    /**
     * The media_player service for a control. Values: SEEK seconds (Number), SHUFFLE / MUTE Boolean,
     * REPEAT a Repeat (or "off"/"one"/"all"), VOLUME 0..1 (Number), GROUP the members (List<String>).
     * Throws IllegalArgumentException for a missing or wrong value.
     */
    fun controlCall(roomId: String, control: Control, value: Any?): HaCall {
        fun data(extra: Map<String, JsonElement> = emptyMap()) =
            JsonObject(mapOf("entity_id" to JsonPrimitive(roomId)) + extra)
        fun bad(): Nothing = throw IllegalArgumentException("$control: bad value $value")
        return when (control) {
            Control.PLAY -> HaCall("media_player", "media_play", data())
            Control.PAUSE -> HaCall("media_player", "media_pause", data())
            Control.NEXT -> HaCall("media_player", "media_next_track", data())
            Control.PREVIOUS -> HaCall("media_player", "media_previous_track", data())
            Control.SEEK -> HaCall("media_player", "media_seek",
                data(mapOf("seek_position" to JsonPrimitive((value as? Number)?.toDouble()?.coerceAtLeast(0.0) ?: bad()))))
            Control.SHUFFLE -> HaCall("media_player", "shuffle_set",
                data(mapOf("shuffle" to JsonPrimitive(value as? Boolean ?: bad()))))
            Control.REPEAT -> {
                val r = when (value) {
                    is Repeat -> value
                    is String -> Repeat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: bad()
                    else -> bad()
                }
                HaCall("media_player", "repeat_set", data(mapOf("repeat" to JsonPrimitive(r.name.lowercase()))))
            }
            Control.VOLUME -> HaCall("media_player", "volume_set",
                data(mapOf("volume_level" to JsonPrimitive((value as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: bad()))))
            Control.MUTE -> HaCall("media_player", "volume_mute",
                data(mapOf("is_volume_muted" to JsonPrimitive(value as? Boolean ?: bad()))))
            Control.GROUP -> {
                val members = (value as? List<*>)?.map { it as? String ?: bad() } ?: bad()
                joinCall(roomId, members)
            }
        }
    }

    fun joinCall(leaderId: String, memberIds: List<String>) =
        HaCall("media_player", "join", buildJsonObject {
            put("entity_id", leaderId)
            putJsonArray("group_members") { memberIds.filter { it != leaderId }.distinct().forEach { add(JsonPrimitive(it)) } }
        })

    fun unjoinCall(roomId: String) = HaCall("media_player", "unjoin", buildJsonObject { put("entity_id", roomId) })

    fun transferCall(fromRoomId: String, toRoomId: String) =
        HaCall("music_assistant", "transfer_queue", buildJsonObject {
            put("entity_id", toRoomId)
            put("source_player", fromRoomId)
            put("auto_play", true)
        })

    // ---- images (C8) ----

    /**
     * `<voice base>/music/image?u=<url-encoded>&size=256`: the hub fetches library and search
     * thumbnails for the device (they point at MA's LAN imageproxy or a streaming CDN). Null when the
     * device isn't linked to Odysseus or there's no image: the page shows a placeholder.
     */
    fun hubImageUrl(voiceBase: String?, linked: Boolean, url: String?, size: Int = IMAGE_SIZE): String? {
        val base = voiceBase?.trim()?.trimEnd('/')
        if (!linked || base.isNullOrEmpty() || !base.startsWith("http")) return null
        if (url.isNullOrBlank()) return null
        val u = URLEncoder.encode(url, "UTF-8").replace("+", "%20")
        return "$base/music/image?u=$u&size=${size.coerceIn(64, 512)}"
    }
}
