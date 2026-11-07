package com.msp1974.vacompanion.odysseus.home.players

// Odysseus fork: reads Home Assistant's media players and sends their transport commands. REST,
// with the access token the Echo already holds for the dashboard (the weather uses the same):
//   POST /api/template                          playing/paused media players with their track
//   POST /api/services/media_player/<service>   play, pause, next, previous, shuffle, repeat
// HTTP goes through HaHttp so it is unit-tested with a fake (OtherPlayersClientTest).

import com.msp1974.vacompanion.odysseus.HaHttp
import com.msp1974.vacompanion.odysseus.UrlConnectionHaHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OtherPlayersException(message: String, val code: Int = 0) : Exception(message)

class OtherPlayersClient(
    private val haUrl: String,
    private val http: HaHttp = UrlConnectionHaHttp,
) {
    /** The media players that are playing, buffering or paused, with HA's clock. */
    fun snapshot(token: String): HaMediaSnapshot {
        val body = buildJsonObject { put("template", PLAYERS_TEMPLATE) }.toString()
        val r = http.call("POST", "$haUrl/api/template", token, "application/json", body)
        if (r.code != 200) throw OtherPlayersException("Home Assistant answered HTTP ${r.code} to the template", r.code)
        val root = runCatching { Json.parseToJsonElement(r.body) }.getOrNull()
        return OtherPlayersRules.parse(root)
            ?: throw OtherPlayersException("Unexpected media player list from Home Assistant")
    }

    /** Sends [call] (media_player.<service>). */
    fun send(token: String, call: HaServiceCall) {
        val r = http.call(
            "POST", "$haUrl/api/services/media_player/${call.service}", token, "application/json", call.data.toString(),
        )
        if (r.code !in 200..299) {
            throw OtherPlayersException("Home Assistant answered HTTP ${r.code} to media_player.${call.service}", r.code)
        }
    }

    companion object {
        /**
         * Every media player that is playing, buffering or paused. Attributes are read with get()
         * so a missing one is null rather than an undefined value tojson can't write. Times are
         * epoch seconds on HA's clock; 'ma' marks a Music Assistant player.
         */
        internal const val PLAYERS_TEMPLATE =
            "{% set ns = namespace(l=[]) %}" +
                "{% for s in states.media_player if s.state in ['playing', 'paused', 'buffering'] %}" +
                "{% set a = s.attributes %}" +
                "{% set ns.l = ns.l + [{'id': s.entity_id, 'name': s.name, 'state': s.state, " +
                "'area': area_name(s.entity_id), " +
                "'title': a.get('media_title'), 'artist': a.get('media_artist'), " +
                "'album': a.get('media_album_name'), 'picture': a.get('entity_picture'), " +
                "'duration': a.get('media_duration'), 'position': a.get('media_position'), " +
                "'position_at': as_timestamp(a.get('media_position_updated_at'), none), " +
                "'features': a.get('supported_features', 0), " +
                "'shuffle': a.get('shuffle'), 'repeat': a.get('repeat'), " +
                "'changed': as_timestamp(s.last_changed), " +
                "'ma': 'mass_player_type' in a}] %}" +
                "{% endfor %}" +
                "{{ {'now': as_timestamp(now()), 'players': ns.l} | tojson }}"
    }
}
