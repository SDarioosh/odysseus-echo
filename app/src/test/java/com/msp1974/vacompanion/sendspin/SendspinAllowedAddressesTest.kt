package com.msp1974.vacompanion.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinAllowedAddressesTest {
    @Test
    fun autoStaysAuto() {
        assertEquals("", SendspinAllowedAddresses.of("", "100.64.0.10"))
        assertEquals("", SendspinAllowedAddresses.of("  ", ""))
    }

    @Test
    fun lanDeviceKeepsTheOneAddress() {
        // HA reaches a LAN device from the same address as the MA URL.
        assertEquals("192.168.1.10", SendspinAllowedAddresses.of("192.168.1.10", "192.168.1.10"))
    }

    @Test
    fun tailscaleDeviceAlsoAllowsHomeAssistantsAddress() {
        assertEquals(
            "192.168.1.10,100.64.0.10",
            SendspinAllowedAddresses.of("192.168.1.10", "100.64.0.10"),
        )
    }

    @Test
    fun notPairedYetOrNotIpv4KeepsThePreferredServerOnly() {
        assertEquals("192.168.1.10", SendspinAllowedAddresses.of("192.168.1.10", ""))
        assertEquals("192.168.1.10", SendspinAllowedAddresses.of("192.168.1.10", "fd7a:115c:a1e0::1"))
        assertEquals("192.168.1.10", SendspinAllowedAddresses.of("192.168.1.10", "ha.local"))
    }

    @Test
    fun ipv4Check() {
        assertTrue(SendspinAllowedAddresses.isIpv4("100.64.0.10"))
        assertFalse(SendspinAllowedAddresses.isIpv4("256.1.1.1"))
        assertFalse(SendspinAllowedAddresses.isIpv4("1.2.3"))
    }
}
