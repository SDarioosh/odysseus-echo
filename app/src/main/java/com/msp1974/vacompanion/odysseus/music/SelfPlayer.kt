package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: which Music Assistant player is this device (plan contract I3). Pure, unit-tested
// (SelfPlayerTest). MA's player id is the Sendspin client id (SendspinSecurity.state.clientId), and
// Home Assistant's MA device has the identifier ["music_assistant", <player id>], so "Play here"
// needs no setup. The rules, in order, are in docs/odysseus/music.md.

data class Candidate(val entityId: String, val ids: List<List<String>>, val name: String,
                     val area: String, val type: String?, val available: Boolean)
enum class How { OVERRIDE, ID, LEGACY_ID, NAME, AREA }
data class SelfMatch(val entityId: String, val how: How, val staleOverride: Boolean = false)

private const val MA_DOMAIN = "music_assistant"

/** The player id this app had before Sendspin v0.9 derived it from the device key. */
fun legacyPlayerId(uuid: String): String = "vaca-$uuid"

/** The name MA gives a VACA Sendspin player it hasn't been told another name for. */
fun defaultPlayerName(uuid: String): String = "VACA $uuid Sendspin Player"

/** True when [override] is set but no longer one of [candidates] (a room was removed or renamed in HA). */
fun isStaleOverride(override: String?, candidates: List<Candidate>): Boolean =
    !override.isNullOrBlank() && candidates.none { it.entityId == override }

fun matchSelf(clientId: String?, uuid: String, area: String?, override: String?,
              candidates: List<Candidate>): SelfMatch? {
    // 0. The on-device setting "This device's player", while it still exists.
    if (!override.isNullOrBlank() && candidates.any { it.entityId == override }) {
        return SelfMatch(override, How.OVERRIDE)
    }
    val stale = isStaleOverride(override, candidates)
    fun hasId(c: Candidate, id: String) = c.ids.any { it.size >= 2 && it[0] == MA_DOMAIN && it[1] == id }

    // 1. MA's player id = the Sendspin client id.
    if (!clientId.isNullOrBlank()) {
        candidates.firstOrNull { hasId(it, clientId) }?.let { return SelfMatch(it.entityId, How.ID, stale) }
    }
    if (uuid.isNotBlank()) {
        // 2. The id from before the key-derived one, while that player still answers.
        val legacy = legacyPlayerId(uuid)
        candidates.firstOrNull { it.available && hasId(it, legacy) }?.let {
            return SelfMatch(it.entityId, How.LEGACY_ID, stale)
        }
        // 3. MA's default name for this device's player, if exactly one has it.
        val name = defaultPlayerName(uuid)
        candidates.filter { it.name.trim().equals(name, ignoreCase = true) }.singleOrNull()?.let {
            return SelfMatch(it.entityId, How.NAME, stale)
        }
    }
    // 4. A guess: the one single player in this device's area that isn't another VACA device's.
    if (!area.isNullOrBlank()) {
        val mine = setOfNotNull(clientId?.takeIf { it.isNotBlank() }, uuid.takeIf { it.isNotBlank() }?.let(::legacyPlayerId))
        candidates.filter {
            it.type == "player" && it.area.trim().equals(area.trim(), ignoreCase = true) &&
                !isOtherVacaPlayer(it, mine)
        }.singleOrNull()?.let { return SelfMatch(it.entityId, How.AREA, stale) }
    }
    return null
}

private val VACA_NAME = Regex("""VACA .+ Sendspin Player""", RegexOption.IGNORE_CASE)
private val SENDSPIN_KEY_ID = Regex("""[A-Za-z0-9_-]{43}""")
private val LEGACY_ID = Regex("""vaca-.+""")

/**
 * True when [c] looks like another VACA device's Sendspin player, so the AREA guess doesn't claim
 * it (two Echos in one room): MA's default name for one (`VACA <uuid> Sendspin Player`), or a
 * `music_assistant` id shaped like a Sendspin client id (43 characters of base64url, derived from
 * the device key) or the legacy `vaca-<uuid>`, other than this device's own ids [mine]. Other MA
 * players have `music_assistant` ids too (e.g. MA's own player ids), which are not of that shape.
 */
fun isOtherVacaPlayer(c: Candidate, mine: Set<String>): Boolean {
    val maIds = c.ids.filter { it.size >= 2 && it[0] == MA_DOMAIN }.map { it[1] }
    if (maIds.any { it in mine }) return false
    return VACA_NAME.matches(c.name.trim()) ||
        maIds.any { SENDSPIN_KEY_ID.matches(it) || LEGACY_ID.matches(it) }
}
