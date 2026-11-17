package com.msp1974.vacompanion.odysseus.power

/**
 * Timing of the device-to-device protocol (broadcasts/VacaUdpBroadcast.kt): the master's
 * keep-alive, how a dead master is noticed and replaced, and presence. Plain Kotlin; times are a
 * monotonic clock in milliseconds. The reasoning is in docs/wake-word-arbitration.md
 * ("Keep-alive and failover").
 *
 * Only the master keeps Wi-Fi out of power save, so the others may miss a broadcast now and then
 * (with the screen off Android may sleep through some beacons). So a device that stops hearing the
 * master doesn't claim at once: it first asks (`master_query`), and a live master answers it
 * directly (unicast, which the access point keeps for a sleeping device instead of dropping).
 * Only when nobody answers within [queryWaitMs] does it claim.
 */
class MasterKeepAlivePolicy(
    val heartbeatMs: Long = HEARTBEAT_INTERVAL_MS,
    val masterTimeoutMs: Long = MASTER_TIMEOUT_MS,
    val queryWaitMs: Long = MASTER_QUERY_WAIT_MS,
) {
    sealed interface Step {
        /** Master: send the keep-alive now. */
        data object Heartbeat : Step
        /** Not heard from the master for too long: ask whether there is one. */
        data object QueryMaster : Step
        /** Asked, and nobody answered: claim (after the election jitter). */
        data object Claim : Step
        /** Nothing to do for [ms]; anything that changes the inputs wakes the loop earlier. */
        data class Wait(val ms: Long) : Step
    }

    /**
     * What the election loop does at [now].
     * [lastHeartbeatAt]: when this device (as master) last sent its keep-alive.
     * [lastMasterSeenAt]: when anything from the master was last heard (0 = never, or forgotten).
     * [queryAt]: when this device last asked for a master (the hello at start-up counts), or null.
     */
    fun next(now: Long, isMaster: Boolean, lastHeartbeatAt: Long, lastMasterSeenAt: Long, queryAt: Long?): Step {
        if (isMaster) {
            val due = lastHeartbeatAt + heartbeatMs
            return if (now >= due) Step.Heartbeat else Step.Wait(due - now)
        }
        // Asked and not answered since: claim once the answer is overdue.
        if (queryAt != null && lastMasterSeenAt < queryAt) {
            val due = queryAt + queryWaitMs
            return if (now >= due) Step.Claim else Step.Wait(due - now)
        }
        val due = lastMasterSeenAt + masterTimeoutMs
        return if (now >= due) Step.QueryMaster else Step.Wait(due - now)
    }

    /** The longest a dead master (gone without a word) can go unnoticed and unreplaced. */
    val worstCaseFailoverMs: Long get() = masterTimeoutMs + queryWaitMs + ELECTION_JITTER_MS

    companion object {
        /** The master's keep-alive (it also carries the master's wake word). Was 2 s. */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** Two keep-alives missed, plus margin, before asking whether the master is still there. Was 6 s. */
        const val MASTER_TIMEOUT_MS = 12_500L

        /** How long to wait for a master to answer a query (or the hello at start-up) before claiming. */
        const val MASTER_QUERY_WAIT_MS = 2_500L

        /** Devices wait a random time up to this before claiming, so the fastest claims. */
        const val ELECTION_JITTER_MS = 500

        /** How often a device that isn't master tells the master it is here (unicast). Was 10 s, broadcast. */
        const val PRESENCE_INTERVAL_MS = 30_000L

        /**
         * When the master forgets a device it hasn't heard from: three presence messages missed,
         * plus margin. Forgetting a device that is still there could let two devices answer;
         * remembering one that has gone only costs the full decision window, so err long. Was 35 s.
         */
        const val PEER_TIMEOUT_MS = 95_000L

        /**
         * The master keeps Wi-Fi out of power save only while other devices could report to it:
         * alone it decides nothing over the network.
         */
        fun masterNeedsLock(isMaster: Boolean, otherDevices: Int): Boolean = isMaster && otherDevices > 0
    }
}
