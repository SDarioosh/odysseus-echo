package com.msp1974.vacompanion.broadcasts

import android.os.SystemClock

/**
 * Picks which device answers a wake word that several heard. The master runs this; it holds no
 * network code so the rules can be tested on their own.
 *
 * Decisions are kept per "group", which the master makes the wake word: devices compete for a wake
 * word wherever they are, and only those that reported hearing it are among the contenders.
 *
 * The rules, in order, among the devices that reported:
 *  0. A device that is responding to someone wins. The wake word interrupts its response, so it is
 *     for that device; the others do nothing. (If several are, the rules below choose among them.)
 *  1. The loudest, by calibrated wake word level - the assumption being that the speaker is nearest
 *     to it, or speaking towards it - unless
 *  2. the device that last handled this wake word (within [LAST_HANDLER_MEMORY_MS]) is within 1.5 dB
 *     of the loudest: too close to call, so it keeps answering.
 *  3. Equal levels: the highest wake word score, then the lowest device id, so every master reaches
 *     the same answer.
 *
 * If any contender didn't report a level (an older version, say), levels are not used at all:
 * the last to answer, then score, then device id.
 *
 * Odysseus fork: upstream let level decide only beyond a 3 dB lead; the rules are now
 * odysseus/arbitration/WakeWordPickRules.kt, which says why.
 */
class WakeWordArbiter(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    /**
     * One device's report of hearing the wake word. [levelDb] is null if it didn't give one.
     * [responding] means it was in the middle of a response when it heard it.
     */
    class Contender(
        val id: String,
        val sender: String,
        val score: Float,
        val levelDb: Float?,
        /** The wake word it heard; null from a version that doesn't say. */
        val wakeWord: String? = null,
        val responding: Boolean = false,
        /** Odysseus fork: the calibrated noise floor it reported, for the decision log only. */
        val noiseFloorDb: Float? = null,
        /** Odysseus fork: when the master got it (elapsed ms), for the decision log only. */
        val receivedAtMs: Long = 0L,
    )

    class Decision(
        val winnerId: String,
        val winner: Contender,
        /** Odysseus fork: the rule that decided, for the log (odysseus/arbitration/WakeWordPickRules.kt). */
        val pick: com.msp1974.vacompanion.odysseus.arbitration.WakeWordPickRules.Pick? = null,
    )

    private class Handled(val sender: String, val at: Long)
    private class Decided(val winnerId: String, val at: Long)

    private val lastHandler = HashMap<String, Handled>()
    private val lastDecision = HashMap<String, Decided>()

    /** Decides for [group] (the wake word) among [contenders]. Null if there were none. */
    @Synchronized
    fun decide(group: String, contenders: List<Contender>): Decision? {
        if (contenders.isEmpty()) return null
        val now = clock()
        val handler = lastHandler[group]?.takeIf { now - it.at <= LAST_HANDLER_MEMORY_MS }?.sender
        val picked = com.msp1974.vacompanion.odysseus.arbitration.WakeWordPickRules.pick(contenders, handler) // Odysseus fork: the rules (odysseus/arbitration/WakeWordPickRules.kt)
        val best = picked.winner
        // A device that interrupted its response didn't answer anything new.
        if (!best.responding) lastHandler[group] = Handled(best.sender, now)
        lastDecision[group] = Decided(best.id, now)
        return Decision(best.id, best, picked)
    }

    /**
     * For a report that arrives after [group]'s window closed: the winner of the decision just made,
     * so the late device can be told it lost instead of waiting and then answering as well. Null if
     * no decision was made lately, meaning this is a fresh wake word.
     */
    @Synchronized
    fun lateVerdict(group: String): String? {
        val decided = lastDecision[group] ?: return null
        return if (clock() - decided.at < LATE_ARRIVAL_MS) decided.winnerId else null
    }

    companion object {
        /** How long the device that last answered is preferred when levels are too close to call. */
        const val LAST_HANDLER_MEMORY_MS = 5 * 60 * 1000L

        /** After a decision, reports for the same wake word arriving within this are late, not new. */
        const val LATE_ARRIVAL_MS = 2_000L

        /**
         * Applies the rules. [contenders] must not be empty. Odysseus fork: the rules are
         * odysseus/arbitration/WakeWordPickRules.kt (rule 0 as above; then the loudest, unless the
         * last to answer is within 1.5 dB of it; then score, then device id).
         */
        fun pick(contenders: List<Contender>, lastHandler: String?): Contender =
            com.msp1974.vacompanion.odysseus.arbitration.WakeWordPickRules.pick(contenders, lastHandler).winner
    }
}
