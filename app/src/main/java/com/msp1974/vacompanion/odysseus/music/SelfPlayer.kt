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
    // 4. A guess: the one single player in this device's area.
    if (!area.isNullOrBlank()) {
        candidates.filter { it.type == "player" && it.area.trim().equals(area.trim(), ignoreCase = true) }
            .singleOrNull()?.let { return SelfMatch(it.entityId, How.AREA, stale) }
    }
    return null
}
