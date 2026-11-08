package com.msp1974.vacompanion.odysseus

// Odysseus fork: the hub's voice-turn feed and satellite post (voice contract v1, §1 and §4).
// Pure data and functions only (no Android), so they are unit-tested on the JVM; the HTTP calls
// are in OdysseusLink.
//
//   GET  <base>/turns/latest?since=<epoch ms>  -> {"turn": VoiceTurn | null}
//   POST <base>/satellite/turn  (multipart)    -> {"ok": true, "context_id": "…", "speaker": … | null}
//
// Unknown fields are ignored, missing ones get defaults, so additive hub changes stay compatible.

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.net.URI

@Serializable
data class TurnLink(
    val url: String = "",
    val label: String? = null,
)

@Serializable
data class TurnDevice(
    val id: String = "",
    val name: String? = null,
    val area: String? = null,
)

@Serializable
data class TurnSpeaker(
    val verified: Boolean = false,
    @SerialName("is_owner") val isOwner: Boolean = false,
    val name: String? = null,
    val score: Double? = null,
)

@Serializable
data class VoiceTurn(
    val id: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val question: String? = null,
    val answer: String? = null,
    val links: List<TurnLink> = emptyList(),
    val actions: List<VoiceAction> = emptyList(),
    /** The spoken confirmation question while a proposed change waits for "yes", else null. */
    @SerialName("awaiting_confirmation") val awaitingConfirmation: String? = null,
    val device: TurnDevice? = null,
    val speaker: TurnSpeaker? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
private data class LatestTurnResponse(val turn: VoiceTurn? = null)

@Serializable
data class SatelliteTurnResponse(
    val ok: Boolean = false,
    @SerialName("context_id") val contextId: String? = null,
    val speaker: TurnSpeaker? = null,
)

/** The /satellite/interrupt answer (a barge-in, BargeIn.kt). */
@Serializable
data class InterruptResponse(val ok: Boolean = false, val cut: Int = 0, val marked: Int = 0)

/** What the device tells the hub when the owner stops speaking (contract §4). */
data class SatelliteTurnForm(
    val deviceId: String,
    val deviceName: String,
    val area: String,
    val wakeWord: String,
    val startedAtMs: Long,
) {
    fun fields(): List<Pair<String, String>> = listOf(
        "device_id" to deviceId,
        "device_name" to deviceName,
        "area" to area,
        "wake_word" to wakeWord,
        "started_at" to startedAtMs.toString(),
    )
}

/** What the turn card shows for one turn. */
data class TurnCardState(
    /** The hub's turn id. */
    val turnId: String,
    val actions: List<VoiceAction> = emptyList(),
    val links: List<TurnLink> = emptyList(),
    /** The confirmation question waiting for a spoken "yes", else null. */
    val awaitingConfirmation: String? = null,
    /** The spoken answer, shown small above its links. */
    val answer: String? = null,
)

object VoiceTurnApi {
    /** Links shown on the card, at most. */
    const val MAX_LINKS = 3

    /** The turn in a /turns/latest body; null for {"turn": null}. Throws on a body that isn't JSON. */
    fun parseLatestTurn(body: String): VoiceTurn? = HubJson.decodeFromString<LatestTurnResponse>(body).turn

    fun parseSatelliteTurnResponse(body: String): SatelliteTurnResponse =
        HubJson.decodeFromString<SatelliteTurnResponse>(body)

    /** The /satellite/interrupt answer: {"ok": true, "cut": n, "marked": n}. Throws on a body that isn't JSON. */
    fun parseInterruptResponse(body: String): OdysseusLink.InterruptResult.Ok =
        HubJson.decodeFromString<InterruptResponse>(body).let { OdysseusLink.InterruptResult.Ok(it.cut, it.marked) }

    /** The links worth a chip: http(s) only, no duplicates, at most [max]. */
    fun displayLinks(links: List<TurnLink>, max: Int = MAX_LINKS): List<TurnLink> =
        links.filter { isWebUrl(it.url) }.distinctBy { it.url }.take(max)

    /** A chip's text: the hub's label, else the link's host. */
    fun linkLabel(link: TurnLink): String =
        link.label?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { URI(link.url).host }.getOrNull()?.removePrefix("www.")
            ?: link.url

    fun isWebUrl(url: String): Boolean {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrEmpty()
    }

    /** False only when the hub names another satellite as the turn's device. */
    fun isFromDevice(turn: VoiceTurn, deviceId: String): Boolean {
        val id = turn.device?.id.orEmpty()
        return id.isEmpty() || deviceId.isEmpty() || id == deviceId
    }

    /** True when the card has something to show for this turn. */
    fun hasContent(turn: VoiceTurn): Boolean =
        turn.actions.isNotEmpty() || displayLinks(turn.links).isNotEmpty() || !turn.awaitingConfirmation.isNullOrBlank()

    /** The card for [turn], or null when the turn did nothing worth showing. */
    fun cardState(turn: VoiceTurn): TurnCardState? {
        if (!hasContent(turn)) return null
        val links = displayLinks(turn.links)
        return TurnCardState(
            turnId = turn.id,
            actions = turn.actions,
            links = links,
            awaitingConfirmation = turn.awaitingConfirmation?.trim()?.takeIf { it.isNotEmpty() },
            answer = turn.answer?.trim()?.takeIf { it.isNotEmpty() && links.isNotEmpty() },
        )
    }

    /** Action rows that fit the Echo Show 5's 480 px height next to the other parts of the card. */
    fun actionRows(state: TurnCardState, maxRows: Int = 4): Int {
        var rows = maxRows
        if (state.links.isNotEmpty()) rows--
        if (state.answer != null) rows--
        if (state.awaitingConfirmation != null) rows--
        return rows.coerceAtLeast(1)
    }

    /** A multipart/form-data body with text [fields] and, when given, one WAV file field "audio". */
    fun multipartBody(boundary: String, fields: List<Pair<String, String>>, audioWav: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(s: String = "") { out.write(s.toByteArray(Charsets.UTF_8)); out.write(CRLF) }
        for ((name, value) in fields) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line()
            line(value.replace(Regex("[\r\n]+"), " "))
        }
        if (audioWav != null) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"audio\"; filename=\"command.wav\"")
            line("Content-Type: audio/wav")
            line()
            out.write(audioWav)
            out.write(CRLF)
        }
        line("--$boundary--")
        return out.toByteArray()
    }

    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
}
