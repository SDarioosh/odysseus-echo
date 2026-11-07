package com.msp1974.vacompanion.odysseus.home.players

// Odysseus fork: music playing on other speakers, read from Home Assistant's media players
// (docs/odysseus/home-screen.md#music-on-other-speakers). The Echo's own Sendspin player only
// hears about its own Music Assistant group, so without this the clock and the now-playing card
// stay empty while another speaker plays. Pure and unit-tested (OtherPlayersRulesTest).

import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/** One Home Assistant media player that is playing or paused with a track. Times are epoch seconds (HA's clock). */
data class HaMediaPlayer(
    val entityId: String,
    val name: String,
    val state: String,
    val area: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** entity_picture: usually a path on Home Assistant with its own access token. */
    val picture: String? = null,
    val durationSec: Double = 0.0,
    val positionSec: Double? = null,
    val positionAt: Double? = null,
    val features: Long = 0,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    /** When the state last changed (e.g. when it was paused). */
    val changedAt: Double = 0.0,
    /** A Music Assistant player: its next/previous work on the queue. */
    val musicAssistant: Boolean = false,
) {
    val playing: Boolean get() = state == "playing" || state == "buffering"
    val trackKey: String get() = "${title.trim().lowercase()}\u0000${artist.trim().lowercase()}"
}

/** The template's answer: HA's clock and the candidate players. */
data class HaMediaSnapshot(val haNow: Double, val players: List<HaMediaPlayer>)

/** A Home Assistant service call: media_player.<service> with [data] (entity_id included). */
data class HaServiceCall(val service: String, val data: JsonObject)

/** What the other-speaker source currently shows. */
data class OtherPlayer(val entityId: String, val name: String, val nowPlaying: SendspinNowPlaying)

object OtherPlayersRules {
    /** A paused player is offered for this long after it was paused, then forgotten. */
    const val PAUSED_KEEP_SEC = 15 * 60.0

    // media_player supported_features bits (homeassistant.components.media_player.MediaPlayerEntityFeature).
    const val FEATURE_PAUSE = 1L
    const val FEATURE_PREVIOUS = 16L
    const val FEATURE_NEXT = 32L
    const val FEATURE_PLAY = 16384L
    const val FEATURE_SHUFFLE = 32768L
    const val FEATURE_REPEAT = 262144L

    private val ENTITY_ID = Regex("^media_player\\.[a-z0-9_]+$")

    fun isMediaPlayerId(id: String) = ENTITY_ID.matches(id)

    /** Reads the template's JSON ({"now": …, "players": […]}); null if it isn't that. */
    fun parse(root: JsonElement?): HaMediaSnapshot? {
        val o = root as? JsonObject ?: return null
        val now = o.num("now") ?: return null
        val list = o["players"] as? JsonArray ?: return null
        val players = list.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val id = p.str("id")?.takeIf { isMediaPlayerId(it) } ?: return@mapNotNull null
            HaMediaPlayer(
                entityId = id,
                name = p.str("name") ?: id,
                state = p.str("state").orEmpty(),
                area = p.str("area").orEmpty(),
                title = p.str("title").orEmpty(),
                artist = p.str("artist").orEmpty(),
                album = p.str("album").orEmpty(),
                picture = p.str("picture")?.takeIf { it.isNotBlank() },
                durationSec = p.num("duration")?.coerceAtLeast(0.0) ?: 0.0,
                positionSec = p.num("position"),
                positionAt = p.num("position_at"),
                features = (p["features"] as? JsonPrimitive)?.longOrNull
                    ?: p.num("features")?.toLong() ?: 0L,
                shuffle = (p["shuffle"] as? JsonPrimitive)?.booleanOrNull ?: false,
                repeat = p.str("repeat")?.takeIf { it in setOf("off", "all", "one") } ?: "off",
                changedAt = p.num("changed") ?: 0.0,
                musicAssistant = (p["ma"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        return HaMediaSnapshot(now, players)
    }

    /**
     * The players worth showing, best first: those with a track, playing or paused within
     * [PAUSED_KEEP_SEC]; this device's room first, then playing before paused, then Music
     * Assistant's entity before another one for the same speaker, then the most recently changed.
     * A track playing on several entities (a group, or a speaker that has a Music Assistant
     * entity beside its own) is listed once.
     */
    fun candidates(snapshot: HaMediaSnapshot, area: String): List<HaMediaPlayer> {
        val room = area.trim().lowercase()
        return snapshot.players
            .filter { it.title.isNotBlank() || it.artist.isNotBlank() }
            .filter { it.playing || (it.state == "paused" && snapshot.haNow - it.changedAt <= PAUSED_KEEP_SEC) }
            .sortedWith(
                compareByDescending<HaMediaPlayer> { room.isNotEmpty() && it.area.trim().lowercase() == room }
                    .thenByDescending { it.playing }
                    .thenByDescending { it.musicAssistant }
                    .thenByDescending { it.changedAt }
                    .thenBy { it.entityId },
            )
            .distinctBy { it.trackKey }
    }

    /**
     * The player to show: [chosen] (an entity id) when set, playing or paused, else the best
     * candidate. Null = nothing to show.
     */
    fun pick(snapshot: HaMediaSnapshot, area: String, chosen: String): HaMediaPlayer? {
        if (chosen.isNotBlank()) {
            return snapshot.players.firstOrNull { it.entityId == chosen }
                ?.takeIf { (it.title.isNotBlank() || it.artist.isNotBlank()) && (it.playing || it.state == "paused") }
        }
        return candidates(snapshot, area).firstOrNull()
    }

    /** The commands the now-playing controls may send, from the player's supported features. */
    fun commands(p: HaMediaPlayer): Set<String> = buildSet {
        if (p.features and FEATURE_PLAY != 0L) add("play")
        if (p.features and FEATURE_PAUSE != 0L) add("pause")
        if (p.features and FEATURE_PREVIOUS != 0L) add("previous")
        if (p.features and FEATURE_NEXT != 0L) add("next")
        if (p.features and FEATURE_SHUFFLE != 0L) { add("shuffle"); add("unshuffle") }
        if (p.features and FEATURE_REPEAT != 0L) { add("repeat_off"); add("repeat_all"); add("repeat_one") }
    }

    /**
     * [p] as the now-playing state the clock and the card draw. [nowMs] is the Echo's
     * elapsedRealtime when the snapshot arrived; the position is moved on from HA's report to
     * HA's "now", so the two clocks never need to agree. [haBase] makes a relative picture
     * path absolute.
     */
    fun toNowPlaying(p: HaMediaPlayer, haNow: Double, nowMs: Long, haBase: String): SendspinNowPlaying {
        val reported = p.positionSec
        val positionSec = when {
            reported == null -> 0.0
            p.playing && p.positionAt != null -> reported + (haNow - p.positionAt).coerceAtLeast(0.0)
            else -> reported
        }
        val durationMs = (p.durationSec * 1000).toLong()
        var positionMs = (positionSec * 1000).toLong().coerceAtLeast(0)
        if (durationMs > 0) positionMs = positionMs.coerceAtMost(durationMs)
        return SendspinNowPlaying(
            title = p.title,
            artist = p.artist,
            album = p.album,
            artworkUrl = pictureUrl(p.picture, haBase),
            durationMs = durationMs,
            positionMs = positionMs,
            positionAtMs = nowMs,
            speed = if (p.playing) 1000 else 0,
            active = true,
            playing = p.playing,
            commands = commands(p),
            shuffle = p.shuffle,
            repeat = p.repeat,
        )
    }

    /** An absolute artwork URL: http(s) as given, a path on Home Assistant joined to [haBase]. */
    fun pictureUrl(picture: String?, haBase: String): String? = when {
        picture.isNullOrBlank() -> null
        picture.startsWith("http://") || picture.startsWith("https://") -> picture
        picture.startsWith("/") -> haBase.removeSuffix("/") + picture
        else -> null
    }

    /**
     * [next] unless it is the same track in the same state as [previous] with a position that
     * only moved as expected: then [previous] is kept, so the screen isn't redrawn every poll.
     */
    fun settle(previous: SendspinNowPlaying?, next: SendspinNowPlaying, nowMs: Long): SendspinNowPlaying {
        if (previous == null) return next
        val same = previous.copy(positionMs = 0, positionAtMs = 0) == next.copy(positionMs = 0, positionAtMs = 0)
        if (!same) return next
        return if (abs(previous.positionNow(nowMs) - next.positionMs) < 2_000) previous else next
    }

    /** The Home Assistant service call for a control's [command], or null if there is none. */
    fun serviceCall(entityId: String, command: String): HaServiceCall? {
        if (!isMediaPlayerId(entityId)) return null
        fun call(service: String, extra: Pair<String, Any>? = null) = HaServiceCall(
            service,
            buildJsonObject {
                put("entity_id", entityId)
                when (val v = extra?.second) {
                    is Boolean -> put(extra.first, v)
                    is String -> put(extra.first, v)
                    else -> Unit
                }
            },
        )
        return when (command) {
            "play" -> call("media_play")
            "pause" -> call("media_pause")
            "next" -> call("media_next_track")
            "previous" -> call("media_previous_track")
            "shuffle" -> call("shuffle_set", "shuffle" to true)
            "unshuffle" -> call("shuffle_set", "shuffle" to false)
            "repeat_off" -> call("repeat_set", "repeat" to "off")
            "repeat_all" -> call("repeat_set", "repeat" to "all")
            "repeat_one" -> call("repeat_set", "repeat" to "one")
            else -> null
        }
    }

    /**
     * Whether the screen shows [other] rather than this Echo's own Sendspin player: the Echo's
     * own music wins while it plays, and while it is paused unless another speaker is playing.
     */
    fun showOther(own: SendspinNowPlaying, other: SendspinNowPlaying?): Boolean {
        if (other == null) return false
        if (own.active && own.hasContent) return false
        if (own.hasContent && !other.playing) return false
        return true
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
}
