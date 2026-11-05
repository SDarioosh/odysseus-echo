package com.msp1974.vacompanion.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinSecurityStateTest {
    private val started = SendspinSecurityState().started("abc", unpairedAccess = true)

    @Test
    fun labelFollowsConnectionAndTrust() {
        assertEquals("Not running", SendspinSecurityState().label())
        assertEquals("Encrypted, no server connected", started.label())
        val connected = started.connection(true, "Music Assistant")
        assertEquals("Encrypted, not paired", connected.label())
        assertEquals("Encrypted, paired with Music Assistant", connected.trust(true).label())
    }

    @Test
    fun successfulPairingClearsCodeAndShowsResult() {
        val s = started.connection(true, "MA")
            .result(0, null, 1_000)
            .code("123456")
        assertTrue(s.pairing)
        assertTrue(s.overlayVisible(1_000))

        val done = s.result(1, null, 2_000).trust(true)
        assertFalse(done.pairing)
        assertNull(done.pairingCode)
        assertTrue(done.paired)
        assertTrue(done.lastResult!!.succeeded)
        // The result stays up a few seconds, then the overlay goes.
        assertTrue(done.overlayVisible(2_000 + SendspinSecurityState.RESULT_SHOWN_MS - 1))
        assertFalse(done.overlayVisible(2_000 + SendspinSecurityState.RESULT_SHOWN_MS))
    }

    @Test
    fun failedPairingKeepsReason() {
        val s = started.connection(true, "MA").result(0, null, 0).code("111111")
            .result(2, "code_mismatch", 10)
        assertNull(s.pairingCode)
        assertFalse(s.lastResult!!.succeeded)
        assertEquals("code_mismatch", s.lastResult!!.reason)
        assertEquals("the code didn't match", SendspinSecurityState.reasonText(s.lastResult!!.reason))
    }

    @Test
    fun disconnectDropsTrustAndPrompts() {
        val s = started.connection(true, "MA").trust(true).code("222222").window(true)
            .connection(false, null)
        assertFalse(s.connected)
        assertFalse(s.paired)
        assertNull(s.pairingCode)
        assertFalse(s.windowRequested)
        assertFalse(s.overlayVisible(0))
        assertEquals("abc", s.clientId)
    }

    @Test
    fun windowRequestShowsOverlay() {
        val s = started.connection(true, "MA").window(true)
        assertTrue(s.overlayVisible(0))
        assertFalse(s.window(false).overlayVisible(0))
    }

    @Test
    fun blankCodeIsNoCode() {
        assertNull(started.code("").pairingCode)
        assertNull(started.code(null).pairingCode)
    }

    @Test
    fun stopKeepsOnlyTheSetting() {
        val s = started.copy(unpairedAccess = false).connection(true, "MA").trust(true).stopped()
        assertFalse(s.running)
        assertFalse(s.unpairedAccess)
        assertEquals("", s.clientId)
    }
}
