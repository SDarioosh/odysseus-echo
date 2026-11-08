package com.msp1974.vacompanion.odysseus.managed

import org.junit.Assert.assertEquals
import org.junit.Test

class SecondsChoicesTest {
    @Test
    fun aValueThatIsNoChipIsAddedInOrderWithNeverLast() {
        val idle = listOf(60, 120, 300, 600, 0)
        assertEquals(idle, secondsChoices(idle, 120))
        assertEquals(listOf(60, 120, 300, 600, 900, 0), secondsChoices(idle, 900))
        assertEquals(listOf(3, 5, 7, 8, 10, 15), secondsChoices(listOf(3, 5, 8, 10, 15), 7))
    }

    @Test
    fun labels() {
        assertEquals("Never", secondsLabel(0))
        assertEquals("15 min", secondsLabel(900))
        assertEquals("8 s", secondsLabel(8))
        assertEquals("90 s", secondsLabel(90))
    }
}
