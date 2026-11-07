package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: the Library's detail views over Home Assistant's Music Assistant actions (E14,
// docs/odysseus/music.md "Library"). HA exposes no "albums of an artist" or "tracks of an album /
// playlist" action, only `search` and `get_library`, so an artist's albums and tracks and an
// album's tracks are found by name and then kept only when they really belong (the artist among
// their artists, the album as their album; by uri or by name). Pure, unit-tested
// (HaMusicDetailTest); HaMusicBackend.detail does the calls.

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What an artist or album holds: [albums] (artists only) and [tracks]. [listed] is false when the
 * backend can't list this kind at all (playlists, radio, …): the view offers only the item itself.
 */
data class MediaDetail(
    val albums: List<MediaItem> = emptyList(),
    val tracks: List<MediaItem> = emptyList(),
    val listed: Boolean = false,
)

object HaMusicDetail {
    /** Asked of `search` per kind for an artist (its albums and its tracks). */
    const val ARTIST_LIMIT = 25

    /** Asked of `search` for an album's tracks. */
    const val ALBUM_SEARCH_LIMIT = 50

    /** Asked of `get_library` (tracks whose text matches the album's name). */
    const val ALBUM_LIBRARY_LIMIT = 100

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    private val TRAILING_VERSION = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")

    /**
     * A name compared loosely: lower case, trimmed, without a trailing "(Deluxe)" / "[Remastered]"
     * (MediaItem names carry MA's version that way). Pure.
     */
    fun norm(name: String?): String {
        var s = name?.trim()?.lowercase() ?: return ""
        while (true) {
            val stripped = TRAILING_VERSION.replace(s, "")
            if (stripped == s || stripped.isEmpty()) break
            s = stripped
        }
        return s.trim()
    }

    /** The item's name for a search query (without the version MediaItem adds). */
    fun queryName(item: MediaItem): String = item.name.let { n ->
        TRAILING_VERSION.replace(n, "").trim().ifEmpty { n.trim() }
    }

    fun artistCall(entry: String, artist: MediaItem) =
        HaCall("music_assistant", "search", buildJsonObject {
            put("config_entry_id", entry)
            put("name", queryName(artist))
            putJsonArray("media_type") { add(JsonPrimitive("album")); add(JsonPrimitive("track")) }
            put("limit", ARTIST_LIMIT)
        })

    fun albumSearchCall(entry: String, album: MediaItem) =
        HaCall("music_assistant", "search", buildJsonObject {
            put("config_entry_id", entry)
            put("name", queryName(album))
            putJsonArray("media_type") { add(JsonPrimitive("track")) }
            put("limit", ALBUM_SEARCH_LIMIT)
        })

    fun albumLibraryCall(entry: String, album: MediaItem) =
        HaCall("music_assistant", "get_library", buildJsonObject {
            put("config_entry_id", entry)
            put("media_type", "track")
            put("search", queryName(album))
            put("order_by", "sort_name")
            put("offset", 0)
            put("limit", ALBUM_LIBRARY_LIMIT)
        })

    /** The objects of a search answer's [key] list, or of a library answer's `items`. */
    private fun objects(response: JsonElement?, key: String): List<JsonObject> {
        val o = response.obj() ?: return emptyList()
        val list = o[key].arr() ?: o["items"].arr() ?: return emptyList()
        return list.mapNotNull { it.obj() }
    }

    private fun sameAs(ref: JsonObject?, item: MediaItem): Boolean {
        if (ref == null) return false
        val uri = ref["uri"].str()
        if (!uri.isNullOrBlank() && uri == item.uri) return true
        val n = norm(ref["name"].str())
        return n.isNotEmpty() && n == norm(item.name)
    }

    /** True when [artist] is among [o]'s artists. */
    fun byArtist(o: JsonObject, artist: MediaItem): Boolean =
        o["artists"].arr()?.any { sameAs(it.obj(), artist) } == true

    /** True when [o]'s album is [album]. */
    fun onAlbum(o: JsonObject, album: MediaItem): Boolean = sameAs(o["album"].obj(), album)

    /** Keeps the first of items with the same uri or the same loose name (library and provider copies). */
    private fun distinct(items: List<MediaItem>): List<MediaItem> {
        val uris = HashSet<String>()
        val names = HashSet<String>()
        return items.filter { uris.add(it.uri) && names.add(norm(it.name)) }
    }

    /** An artist's albums and tracks from an `artistCall` answer, in MA's order. */
    fun parseArtist(response: JsonElement?, artist: MediaItem, image: (String) -> String?): MediaDetail {
        fun kept(key: String, kind: MediaKind) = objects(response, key)
            .filter { byArtist(it, artist) }
            .mapNotNull { HaMusicJson.item(it, image) }
            .filter { it.kind == kind }
        return MediaDetail(
            albums = distinct(kept("albums", MediaKind.ALBUM)),
            tracks = distinct(kept("tracks", MediaKind.TRACK)),
            listed = true,
        )
    }

    /**
     * An album's tracks from the answers of `albumSearchCall` and `albumLibraryCall` (either may be
     * missing): those on [album], disc and track order when MA gives the numbers, else as found.
     */
    fun parseAlbum(responses: List<JsonElement>, album: MediaItem, image: (String) -> String?): MediaDetail {
        val found = responses.flatMap { r -> objects(r, "tracks") }.filter { onAlbum(it, album) }
        val numbered = found.mapIndexed { i, o ->
            Triple(o, (o["disc_number"].num() ?: 0.0) * 10_000 + (o["track_number"].num() ?: 0.0), i)
        }
        val ordered = if (found.any { it["track_number"].num() != null }) {
            numbered.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
        } else found
        val tracks = ordered.mapNotNull { HaMusicJson.item(it, image) }.filter { it.kind == MediaKind.TRACK }
        return MediaDetail(tracks = distinct(tracks), listed = true)
    }
}
