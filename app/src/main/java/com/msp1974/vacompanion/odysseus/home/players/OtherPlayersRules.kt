package com.msp1974.vacompanion.odysseus.home.players

// Odysseus fork: music playing on other speakers (docs/odysseus/home-screen.md#music-on-other-speakers).
// The Echo's own Sendspin player only hears about its own Music Assistant group, so without this
// the clock and the now-playing card stay empty while another speaker plays. The speakers are the
// Music page's list of media players (HaMusicBackend.rooms and .live, music/RoomListing); this
// picks one and turns it into the SendspinNowPlaying the clock and the upstream card draw. Pure,
// unit-tested (OtherPlayersRulesTest).

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.LivePlayer
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.odysseus.music.Room
import com.msp1974.vacompanion.odysseus.player.PlayerClock
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying

/** A listed media player with its live state; [pausedSinceMs] (wall clock) while it is paused. */
data class Speaker(val room: Room, val live: LivePlayer, val pausedSinceMs: Long? = null) {
    val entityId: String get() = room.entityId
    val playing: Boolean get() = OtherPlayersRules.isPlaying(live.status)
    val hasTrack: Boolean get() = !live.player.title.isNullOrBlank() || !live.player.artist.isNullOrBlank()
    val trackKey: String get() =
        "${live.player.title.orEmpty().trim().lowercase()}\u0000${live.player.artist.orEmpty().trim().lowercase()}"
}

/** What the other-speaker source currently shows. */
data class OtherPlayer(val entityId: String, val name: String, val nowPlaying: SendspinNowPlaying)

object OtherPlayersRules {
    /** A paused player is offered for this long after it was paused, then forgotten. */
    const val PAUSED_KEEP_MS = 15 * 60_000L

    fun isPlaying(status: String): Boolean = status == "playing" || status == "buffering"

    /**
     * When each paused player in [live] was paused (wall clock): kept from [previous] while it stays
     * paused, else its last update in Home Assistant (the moment it paused, as the websocket reports
     * it). Players that aren't paused are dropped.
     */
    fun pausedSince(previous: Map<String, Long>, live: Map<String, LivePlayer>): Map<String, Long> =
        live.filterValues { it.status == "paused" }.mapValues { (id, p) -> previous[id] ?: p.updatedMs }

    /** The listed [rooms] with a live state, as speakers. */
    fun speakers(rooms: List<Room>, live: Map<String, LivePlayer>, paused: Map<String, Long>): List<Speaker> =
        rooms.mapNotNull { r -> live[r.entityId]?.let { Speaker(r, it, paused[r.entityId]) } }

    /** Has a track, and is playing (or buffering) or was paused at most [PAUSED_KEEP_MS] ago. */
    fun showable(s: Speaker, nowMs: Long): Boolean =
        s.hasTrack && (s.playing || (s.live.status == "paused" && s.pausedSinceMs != null && nowMs - s.pausedSinceMs <= PAUSED_KEEP_MS))

    /**
     * The speakers worth showing, best first: [showable] ones; this device's area first, then
     * playing before paused, then a Music Assistant player before another one, then the most
     * recently updated. A track playing on several players (a group) is listed once. Another
     * Echo's Sendspin player is a speaker like any other (often in this room); this Echo's own
     * music wins anyway ([showOther]).
     */
    fun candidates(speakers: List<Speaker>, area: String, nowMs: Long): List<Speaker> {
        val room = area.trim().lowercase()
        return speakers
            .filter { showable(it, nowMs) }
            .sortedWith(
                compareByDescending<Speaker> { room.isNotEmpty() && it.room.area?.trim()?.lowercase() == room }
                    .thenByDescending { it.playing }
                    .thenByDescending { it.room.musicAssistant }
                    .thenByDescending { it.live.updatedMs }
                    .thenBy { it.entityId },
            )
            .distinctBy { it.trackKey }
    }

    /** The speaker to show: [chosen] (an entity id) when set and [showable], else the best candidate. */
    fun pick(speakers: List<Speaker>, area: String, chosen: String, nowMs: Long): Speaker? {
        if (chosen.isNotBlank()) return speakers.firstOrNull { it.entityId == chosen }?.takeIf { showable(it, nowMs) }
        return candidates(speakers, area, nowMs).firstOrNull()
    }

    /** The Sendspin-style commands the clock and the card may send, from the player's controls. */
    fun commands(can: Set<Control>): Set<String> = buildSet {
        if (Control.PLAY in can) add("play")
        if (Control.PAUSE in can) add("pause")
        if (Control.PREVIOUS in can) add("previous")
        if (Control.NEXT in can) add("next")
        if (Control.SHUFFLE in can) { add("shuffle"); add("unshuffle") }
        if (Control.REPEAT in can) { add("repeat_off"); add("repeat_all"); add("repeat_one") }
    }

    /** A command from the clock or the card as the Music backend's control and value; null for none. */
    fun control(command: String): Pair<Control, Any?>? = when (command) {
        "play" -> Control.PLAY to null
        "pause" -> Control.PAUSE to null
        "next" -> Control.NEXT to null
        "previous" -> Control.PREVIOUS to null
        "shuffle" -> Control.SHUFFLE to true
        "unshuffle" -> Control.SHUFFLE to false
        "repeat_off" -> Control.REPEAT to Repeat.OFF
        "repeat_all" -> Control.REPEAT to Repeat.ALL
        "repeat_one" -> Control.REPEAT to Repeat.ONE
        else -> null
    }

    /**
     * [live] as the now-playing state the clock and the upstream card draw. The one place a Home
     * Assistant state goes from the wall clock to Sendspin's elapsedRealtime ([wallMinusElapsedMs],
     * PlayerClock); [haBase] makes a relative picture path absolute.
     */
    fun toNowPlaying(live: LivePlayer, haBase: String, wallMinusElapsedMs: Long = PlayerClock.wallMinusElapsed()): SendspinNowPlaying {
        val p: PlayerState = live.player
        val playing = isPlaying(live.status)
        return SendspinNowPlaying(
            title = p.title.orEmpty(),
            artist = p.artist.orEmpty(),
            album = p.album.orEmpty(),
            artworkUrl = pictureUrl(p.artUrl, haBase),
            durationMs = ((p.durationS ?: 0.0) * 1000).toLong(),
            positionMs = ((p.positionS ?: 0.0) * 1000).toLong(),
            positionAtMs = PlayerClock.wallToElapsed(p.positionAtMs, wallMinusElapsedMs),
            speed = if (playing) 1000 else 0,
            active = true,
            playing = playing,
            commands = commands(p.can),
            shuffle = p.shuffle,
            repeat = p.repeat.name.lowercase(),
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
     * Whether the screen shows [other] rather than this Echo's own Sendspin player: the Echo's
     * own music wins while it plays, and while it is paused unless another speaker is playing.
     */
    fun showOther(own: SendspinNowPlaying, other: SendspinNowPlaying?): Boolean {
        if (other == null) return false
        if (own.active && own.hasContent) return false
        if (own.hasContent && !other.playing) return false
        return true
    }
}
