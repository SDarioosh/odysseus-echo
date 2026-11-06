package com.msp1974.vacompanion.odysseus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.AEADBadTagException

class TokenMemoTest {
    private var now = 1_762_434_000_000L
    private var calls = 0
    private var failures = 0

    private fun memo(lostKey: () -> Boolean) = TokenMemo(
        decrypt = { calls++; if (lostKey()) throw AEADBadTagException() else "fictional-token-for-$it" },
        retryMs = 600_000L,
        clock = { now },
        onFailure = { failures++ },
    )

    @Test
    fun aReadableTokenIsDecryptedOncePerSavedValue() {
        val m = memo { false }
        repeat(5) { assertEquals("fictional-token-for-enc1", m.read("enc1")) }
        assertEquals(1, calls)
        assertEquals("fictional-token-for-enc2", m.read("enc2"))
        assertEquals(2, calls)
        assertNull(m.read(null))
        assertNull(m.read(""))
    }

    @Test
    fun anUnreadableTokenIsNoTokenAndIsNotTriedOnEveryRequest() {
        var lost = true
        val m = memo { lost }
        repeat(20) { assertNull(m.read("enc1")) }
        assertEquals(1, calls)
        assertEquals(1, failures)
        // Tried again later (a passing Keystore problem)...
        now += 600_000L
        lost = false
        assertEquals("fictional-token-for-enc1", m.read("enc1"))
        // ...and at once when a new token is saved.
        lost = true
        assertNull(m.read("enc2"))
        lost = false
        assertEquals("fictional-token-for-enc3", m.read("enc3"))
    }
}
