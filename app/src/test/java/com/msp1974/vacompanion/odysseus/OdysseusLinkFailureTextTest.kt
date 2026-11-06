package com.msp1974.vacompanion.odysseus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OdysseusLinkFailureTextTest {
    @Test
    fun anUnreadableTurnIsReportedWithoutItsWords() {
        val body = """{"turn": {"turn_id": 5, "answer": "Fictional: your PIN is 1234", "actions": "oops"}}"""
        val e = runCatching { VoiceTurnApi.parseLatestTurn(body) }.exceptionOrNull()!!
        val text = OdysseusLink.failureText(e)
        assertFalse(text, text.contains("PIN"))
        assertEquals("connect timed out", OdysseusLink.failureText(java.net.SocketTimeoutException("connect timed out")))
    }
}
