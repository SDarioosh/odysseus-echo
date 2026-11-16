package com.msp1974.vacompanion.odysseus.power

import com.msp1974.vacompanion.odysseus.power.MasterKeepAlivePolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterKeepAlivePolicyTest {

    private val policy = MasterKeepAlivePolicy()
    private val heartbeat = MasterKeepAlivePolicy.HEARTBEAT_INTERVAL_MS
    private val timeout = MasterKeepAlivePolicy.MASTER_TIMEOUT_MS
    private val queryWait = MasterKeepAlivePolicy.MASTER_QUERY_WAIT_MS

    private fun master(now: Long, lastHeartbeat: Long) = policy.next(now, true, lastHeartbeat, 0, null)
    private fun follower(now: Long, lastSeen: Long, queryAt: Long? = null) = policy.next(now, false, 0, lastSeen, queryAt)

    // ---- the master

    @Test
    fun theMasterSendsItsKeepAliveWhenDueAndSleepsUntilThen() {
        assertEquals(Step.Heartbeat, master(now = 50_000, lastHeartbeat = 0))
        assertEquals(Step.Wait(heartbeat - 1_000), master(now = 1_000, lastHeartbeat = 0))
        assertEquals(Step.Heartbeat, master(now = heartbeat, lastHeartbeat = 0))
    }

    @Test
    fun theMasterNeverQueriesOrClaims() {
        // However long since it heard another master, and with a query outstanding.
        assertEquals(Step.Wait(heartbeat), policy.next(1_000_000, true, 1_000_000, 0, 999_000))
    }

    // ---- a device that isn't master

    @Test
    fun aDeviceHearingTheMasterJustWaitsForTheTimeout() {
        assertEquals(Step.Wait(timeout - 4_000), follower(now = 5_000, lastSeen = 1_000))
    }

    @Test
    fun twoMissedKeepAlivesAreTolerated() {
        // Heard at 0; keep-alives at 10 s and 20 s missed (power save); still no query.
        assertTrue(follower(now = 2 * heartbeat + 1, lastSeen = 0) is Step.Wait)
        assertEquals(Step.QueryMaster, follower(now = timeout, lastSeen = 0))
    }

    @Test
    fun aDeviceAsksBeforeItClaims() {
        assertEquals(Step.QueryMaster, follower(now = 30_000, lastSeen = 0))
        // Asked at 30 s: waits for an answer...
        assertEquals(Step.Wait(queryWait - 1_000), follower(now = 31_000, lastSeen = 0, queryAt = 30_000))
        // ...and claims only when none came.
        assertEquals(Step.Claim, follower(now = 30_000 + queryWait, lastSeen = 0, queryAt = 30_000))
    }

    @Test
    fun anAnswerToTheQueryMeansNoClaim() {
        // The master answered (unicast) at 31 s: back to waiting for its keep-alive.
        assertEquals(Step.Wait(timeout - 2_000), follower(now = 33_000, lastSeen = 31_000, queryAt = 30_000))
    }

    @Test
    fun atStartUpTheHelloIsTheQuery() {
        // Nothing heard yet, hello sent at 100: claim after the query wait, as before (2.5 s).
        assertEquals(Step.Wait(queryWait), follower(now = 100, lastSeen = 0, queryAt = 100))
        assertEquals(Step.Claim, follower(now = 100 + queryWait, lastSeen = 0, queryAt = 100))
    }

    @Test
    fun aMasterAnsweringTheStartUpHelloIsAdopted() {
        assertTrue(follower(now = 3_000, lastSeen = 500, queryAt = 100) is Step.Wait)
    }

    @Test
    fun aForcedElectionAsksAtOnce() {
        // A decision never came: VacaUdpBroadcast forgets the master (last seen 0) and clears the query.
        assertEquals(Step.QueryMaster, follower(now = 40_000, lastSeen = 0, queryAt = null))
    }

    // ---- failover and presence

    @Test
    fun aDeadMasterIsReplacedWithinTheDocumentedTime() {
        assertEquals(timeout + queryWait + MasterKeepAlivePolicy.ELECTION_JITTER_MS, policy.worstCaseFailoverMs)
        assertTrue(policy.worstCaseFailoverMs <= 30_000)
    }

    @Test
    fun aPeerIsForgottenOnlyAfterSeveralMissedPresences() {
        assertTrue(MasterKeepAlivePolicy.PEER_TIMEOUT_MS >= 3 * MasterKeepAlivePolicy.PRESENCE_INTERVAL_MS)
    }

    @Test
    fun theMasterKeepsWifiAwakeOnlyWithOtherDevicesAround() {
        assertTrue(MasterKeepAlivePolicy.masterNeedsLock(isMaster = true, otherDevices = 1))
        assertFalse(MasterKeepAlivePolicy.masterNeedsLock(isMaster = true, otherDevices = 0))
        assertFalse(MasterKeepAlivePolicy.masterNeedsLock(isMaster = false, otherDevices = 3))
    }
}
