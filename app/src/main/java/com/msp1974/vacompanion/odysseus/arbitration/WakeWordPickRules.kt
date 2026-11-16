package com.msp1974.vacompanion.odysseus.arbitration

import com.msp1974.vacompanion.broadcasts.WakeWordArbiter.Contender

/**
 * Odysseus fork: which of the devices that heard a wake word answers it. The master runs it through
 * broadcasts/WakeWordArbiter (which keeps the memory of who answered last and the late verdicts);
 * this is only the choice, so every rule has a test (WakeWordPickRulesTest).
 * docs/wake-word-arbitration.md, "It picks one".
 *
 * Among the devices that reported:
 *  0. **Responding.** A device in the middle of a response wins (the wake word interrupts it); if
 *     several are, the rules below choose among them. Unchanged from upstream.
 *  1. **Loudest.** The device with the loudest calibrated wake word level wins, however small the
 *     gap, with one exception:
 *  2. **Last answered, when too close to call.** The device that answered this wake word last (within
 *     5 minutes) wins if its level is within [STICKY_MARGIN_DB] of the loudest. That keeps one device
 *     answering someone standing between two, instead of flipping on noise.
 *  3. **Equal levels:** the highest wake word score, then the lowest device id (every master reaches
 *     the same answer).
 * If any contender gave no level (an older version), levels can't be compared: last answered, then
 * score, then device id.
 *
 * Upstream let level decide only beyond a 3 dB lead, and then went to "last answered" and the score.
 * In a room, two devices a metre or two apart often differ by less than 3 dB, so the device that
 * happened to answer last kept winning for 5 minutes (each win renews it) even when the speaker had
 * walked up to the other one; and the score, which saturates near 1.0 for anyone close enough to
 * hear clearly, then picked at random. A level gap of 2 dB is real evidence of who is nearer or being
 * spoken towards; staying with the last device is now only for a gap of noise size.
 */
object WakeWordPickRules {

    /** Levels this close to the loudest are too close to call: the device that answered last keeps it. */
    const val STICKY_MARGIN_DB = 1.5f

    enum class Rule(val text: String) {
        ONLY_ONE("only one heard it"),
        RESPONDING("responding: interrupting its response"),
        LOUDEST("loudest"),
        LAST_ANSWERED("answered last, within ${STICKY_MARGIN_DB} dB of the loudest"),
        SCORE("equal level, highest score"),
        DEVICE_ID("equal level and score, lowest device id"),
        LAST_ANSWERED_NO_LEVEL("answered last (not every device gave a level)"),
        SCORE_NO_LEVEL("highest score (not every device gave a level)"),
        DEVICE_ID_NO_LEVEL("lowest device id (not every device gave a level)"),
    }

    /** The winner, the rule that decided, and for a level decision its lead over the runner-up in dB. */
    class Pick(val winner: Contender, val rule: Rule, val leadDb: Float? = null)

    /** [contenders] must not be empty. [lastHandler] is the sender that last answered, if remembered. */
    fun pick(contenders: List<Contender>, lastHandler: String?): Pick {
        require(contenders.isNotEmpty())
        val responding = contenders.filter { it.responding }
        val candidates = responding.ifEmpty { contenders }
        if (candidates.size == 1) {
            return Pick(candidates.first(), if (responding.isNotEmpty()) Rule.RESPONDING else Rule.ONLY_ONE)
        }

        if (candidates.all { it.levelDb != null }) {
            val ranked = candidates.sortedWith(
                compareByDescending<Contender> { it.levelDb!! }.thenByDescending { it.score }.thenBy { it.sender }
            )
            val loudest = ranked[0]
            val handler = ranked.firstOrNull { it.sender == lastHandler }
            if (handler != null && handler !== loudest && loudest.levelDb!! - handler.levelDb!! <= STICKY_MARGIN_DB) {
                return Pick(handler, Rule.LAST_ANSWERED, handler.levelDb - loudest.levelDb)
            }
            val next = ranked[1]
            val lead = loudest.levelDb!! - next.levelDb!!
            val rule = when {
                lead > 0f -> Rule.LOUDEST
                loudest.score > next.score -> Rule.SCORE
                else -> Rule.DEVICE_ID
            }
            return Pick(loudest, rule, lead)
        }

        candidates.firstOrNull { it.sender == lastHandler }?.let { return Pick(it, Rule.LAST_ANSWERED_NO_LEVEL) }
        val ranked = candidates.sortedWith(compareByDescending<Contender> { it.score }.thenBy { it.sender })
        val rule = if (ranked[0].score > ranked[1].score) Rule.SCORE_NO_LEVEL else Rule.DEVICE_ID_NO_LEVEL
        return Pick(ranked[0], rule)
    }

    /**
     * Whether the decision's winning report [winnerId] is one of this device's own ([uuid]). Report ids
     * are "<uuid>-<n>". A barge-in reports twice: the interruption (responding, so it wins at once),
     * then the new wake word, which arrives after that decision and gets it as a late verdict. That
     * decision is this device's, so it answers rather than "losing" to itself.
     */
    fun isOwnReport(winnerId: String, uuid: String): Boolean = uuid.isNotEmpty() && winnerId.startsWith("$uuid-")
}
