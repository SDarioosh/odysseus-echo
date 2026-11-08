package com.msp1974.vacompanion.odysseus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NevermindTest {
    @Test
    fun `never mind ends a wake word turn, however it is written`() {
        for (t in listOf("never mind", "Never mind.", "Nevermind!", "Athena, never mind.", "Oh, never mind then", "  NEVER MIND  "))
            assertTrue(t, Nevermind.cancels(t, continuation = false))
    }

    @Test
    fun `other cancel phrases end a wake word turn`() {
        for (t in listOf("Cancel.", "Forget it.", "Stop talking.", "Be quiet!", "Shut up.", "Nothing.", "That's all.", "That’s all", "No thanks."))
            assertTrue(t, Nevermind.cancels(t, continuation = false))
    }

    @Test
    fun `a real question is not cancelled`() {
        for (t in listOf("Never mind the weather, what time is it?", "Cancel my dentist appointment", "What's nothing in French?", "Stop the music", "Stop", ""))
            assertFalse(t, Nevermind.cancels(t, continuation = false))
    }

    @Test
    fun `in a continued conversation only never mind cancels, so no and cancel reach Odysseus`() {
        assertTrue(Nevermind.cancels("Never mind.", continuation = true))
        assertTrue(Nevermind.cancels("Nevermind", continuation = true))
        assertFalse(Nevermind.cancels("Cancel.", continuation = true))
        assertFalse(Nevermind.cancels("No thanks.", continuation = true))
    }

    @Test
    fun `normalize drops punctuation and filler at the ends`() {
        assertEquals("never mind", Nevermind.normalize("Hey Athena, never mind... please"))
    }
}
