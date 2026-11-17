package com.msp1974.vacompanion.odysseus.arbitration

import com.msp1974.vacompanion.broadcasts.WakeWordArbiter.Contender
import java.util.Locale

/**
 * Odysseus fork: the master's log line for a decision, with every reporter's inputs, so arbitration
 * can be tuned from real logs (`adb logcat -s VacaUdpBroadcast`; docs/wake-word-arbitration.md,
 * "Debugging"). Per report: the device (first 8 characters of its id), its calibrated wake word
 * level and noise floor, its score, when the master got it after the first report, and whether it
 * was responding. Example:
 *
 * `winner 1a2b3c4d by loudest (+2.4 dB) from 2 report(s): 1a2b3c4d -29.6 dB (floor -61.0), score 0.99, +0 ms | 5e6f7a8b -32.0 dB (floor -58.3), score 0.97, +23 ms`
 */
object WakeDecisionLog {

    fun decision(pick: WakeWordPickRules.Pick, reports: List<Contender>): String {
        val lead = pick.leadDb?.let { " (%+.1f dB)".format(Locale.ROOT, it) } ?: ""
        return "winner ${short(pick.winner.sender)} by ${pick.rule.text}$lead from ${reports.size} report(s): " +
            reports(reports)
    }

    fun reports(reports: List<Contender>): String {
        val first = reports.minOfOrNull { it.receivedAtMs } ?: 0L
        return reports.joinToString(" | ") { report(it, first) }
    }

    fun report(c: Contender, firstReceivedAtMs: Long = c.receivedAtMs): String {
        val level = c.levelDb?.let { "%.1f dB".format(Locale.ROOT, it) } ?: "? dB"
        val floor = c.noiseFloorDb?.let { " (floor %.1f)".format(Locale.ROOT, it) } ?: ""
        val score = "score %.2f".format(Locale.ROOT, c.score)
        val at = if (c.receivedAtMs > 0L) ", +${c.receivedAtMs - firstReceivedAtMs} ms" else ""
        val responding = if (c.responding) ", responding" else ""
        return "${short(c.sender)} $level$floor, $score$at$responding"
    }

    private fun short(sender: String) = sender.take(8)
}
