package com.msp1974.vacompanion.odysseus.ha

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class HaAuthRetryTest {
    @Test
    fun noRefreshWhenTheTokenWorks() = runBlocking {
        val auth = FakeHaAuth()
        assertEquals("ok T1", withAuthRetry(auth) { "ok $it" })
        assertEquals(listOf(false), auth.refreshes)
    }

    @Test
    fun oneRefreshAfterA401() = runBlocking {
        val auth = FakeHaAuth()
        val tokens = mutableListOf<String>()
        val r = withAuthRetry(auth) { t ->
            tokens += t
            if (t == "T1") throw HaException("401", 401)
            "ok"
        }
        assertEquals("ok", r)
        assertEquals(listOf("T1", "T2"), tokens)
        assertEquals(listOf(false, true), auth.refreshes)
    }

    @Test
    fun aSecond401IsThrownWithoutAnotherTry() = runBlocking {
        val auth = FakeHaAuth()
        var tries = 0
        try {
            withAuthRetry<String>(auth) { tries++; throw HaException("401", 401) }
            fail()
        } catch (e: HaException) {
            assertEquals(401, e.code)
        }
        assertEquals(2, tries)
        assertEquals(listOf(false, true), auth.refreshes)
    }

    @Test
    fun otherErrorsAreNotRetried() = runBlocking {
        val auth = FakeHaAuth()
        var tries = 0
        try {
            withAuthRetry<String>(auth) { tries++; throw HaException("bad", 400) }
            fail()
        } catch (e: HaException) {
            assertEquals(400, e.code)
        }
        assertEquals(1, tries)
    }
}
