package com.msp1974.vacompanion.odysseus.audio

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** The shared microphone's restarts after a failure, and how a dead microphone is noticed. */
class MicRestartTest {

    @Test
    fun failuresAreCountedInARowNotOverTheMicrophonesLife() = runBlocking {
        // The process-wide microphone is collected for days: a failure now and then, each followed
        // by good frames, must not add up to "give up" (upstream counted every failure ever).
        val opens = AtomicInteger()
        val source = flow {
            val n = opens.incrementAndGet()
            emit(n)
            if (n <= 8) throw IOException("read failed")
        }
        val got = source.restartOnFailure(maxInARow = 5, delayMs = 0).toList()
        assertEquals((1..9).toList(), got)
    }

    @Test
    fun itGivesUpAfterTooManyFailuresInARow() = runBlocking {
        val opens = AtomicInteger()
        val source = flow<Int> {
            opens.incrementAndGet()
            throw IOException("no microphone")
        }
        val failed = runCatching { source.restartOnFailure(maxInARow = 5, delayMs = 0).toList() }.exceptionOrNull()
        assertTrue(failed is IOException)   // the last failure goes on (the stream's catch logs it)
        assertEquals(6, opens.get())        // the first try and 5 restarts
    }

    @Test
    fun aMicrophoneThatOnlyGivesEmptyReadsIsDead() {
        // AudioRecord.read returns an error (e.g. ERROR_DEAD_OBJECT) for good: readShort gives
        // empty frames and nothing ever throws, so the restart above never ran.
        val reads = EmptyReads(limit = 3)
        assertFalse(reads.dead(empty = true))
        assertFalse(reads.dead(empty = true))
        assertFalse(reads.dead(empty = false))   // a frame resets it
        assertFalse(reads.dead(empty = true))
        assertFalse(reads.dead(empty = true))
        assertTrue(reads.dead(empty = true))
    }
}
