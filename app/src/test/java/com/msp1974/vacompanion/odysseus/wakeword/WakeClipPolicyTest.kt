package com.msp1974.vacompanion.odysseus.wakeword

import com.msp1974.vacompanion.odysseus.wakeword.WakeClipPolicy.Feed
import com.msp1974.vacompanion.odysseus.wakeword.WakeClipPolicy.Gate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeClipPolicyTest {

    @Test
    fun lockedForgetsEverythingWhateverRuns() {
        assertEquals(Feed.FORGET, WakeClipPolicy.feed(locked = true, catchMisses = true, taking = true))
        assertEquals(Feed.FORGET, WakeClipPolicy.feed(locked = true, catchMisses = false, taking = false))
        assertEquals(Feed.CATCH, WakeClipPolicy.feed(locked = false, catchMisses = false, taking = true))
        assertEquals(Feed.CATCH, WakeClipPolicy.feed(locked = false, catchMisses = true, taking = false))
        assertEquals(Feed.SKIP, WakeClipPolicy.feed(locked = false, catchMisses = false, taking = false))
    }

    @Test
    fun catchesOnlyWhenOnAndUnlocked() {
        assertTrue(WakeClipPolicy.catching(catchMisses = true, locked = false))
        assertFalse(WakeClipPolicy.catching(catchMisses = true, locked = true))
        assertFalse(WakeClipPolicy.catching(catchMisses = false, locked = false))
    }

    @Test
    fun keepsNothingWhileLockedOrWithoutAWakeWord() {
        assertEquals("hey_athena", WakeClipPolicy.keepAs(locked = false, wakeWord = "hey_athena"))
        assertNull(WakeClipPolicy.keepAs(locked = true, wakeWord = "hey_athena"))
        assertNull(WakeClipPolicy.keepAs(locked = false, wakeWord = "none"))
        assertNull(WakeClipPolicy.keepAs(locked = false, wakeWord = " "))
        assertNull(WakeClipPolicy.keepAs(locked = false, wakeWord = null))
    }

    @Test
    fun sendsOnlyWhenUnlockedAndLinked() {
        assertEquals(Gate.NOTHING, WakeClipPolicy.uploadGate(waiting = 0, locked = true, linked = false))
        assertEquals(Gate.LOCKED, WakeClipPolicy.uploadGate(waiting = 2, locked = true, linked = true))
        assertEquals(Gate.NOT_LINKED, WakeClipPolicy.uploadGate(waiting = 2, locked = false, linked = false))
        assertEquals(Gate.GO, WakeClipPolicy.uploadGate(waiting = 2, locked = false, linked = true))
        assertNull(WakeClipPolicy.problem(Gate.GO))
        assertTrue(WakeClipPolicy.problem(Gate.LOCKED)!!.startsWith("Locked"))
    }

    @Test
    fun backsOffAfterFailures() {
        assertNull(WakeClipPolicy.waitMs(ClipPass.DONE, 0))
        assertEquals(60_000L, WakeClipPolicy.waitMs(ClipPass.RETRY, 1))
        assertEquals(180_000L, WakeClipPolicy.waitMs(ClipPass.RETRY, 3))
        assertEquals(15 * 60_000L, WakeClipPolicy.waitMs(ClipPass.RETRY, 40))
        assertEquals(60 * 60_000L, WakeClipPolicy.waitMs(ClipPass.AUTH, 0))
        assertNull(WakeClipPolicy.problem(UploadResult(ClipPass.DONE)))
        assertEquals(
            "Odysseus didn't take the clips (HTTP 503); will try again",
            WakeClipPolicy.problem(UploadResult(ClipPass.RETRY, "HTTP 503")),
        )
    }
}
