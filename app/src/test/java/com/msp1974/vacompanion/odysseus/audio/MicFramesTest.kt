package com.msp1974.vacompanion.odysseus.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MicFramesTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** A fake microphone: counts how often it is opened and closed, and how many are open. */
    private class FakeMic {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val open get() = opened.get() - closed.get()

        // Frame n: raw carries n, processed carries -n, so the two are easy to tell apart.
        val frames: Flow<MicFrame> = flow {
            opened.incrementAndGet()
            try {
                var n = 1
                while (true) {
                    emit(MicFrame(shortArrayOf(n.toShort()), shortArrayOf((-n).toShort())))
                    n++
                    delay(2)
                }
            } finally {
                closed.incrementAndGet()
            }
        }
    }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting until $what", e)
        }
    }

    @Test
    fun theMicrophoneStaysClosedUntilSomethingListens() = runBlocking {
        val mic = FakeMic()
        MicFrameSharing(mic.frames, scope, 16)
        delay(100)
        assertEquals(0, mic.opened.get())
    }

    @Test
    fun aRawOnlySubscriberOpensTheMicrophoneAndGetsRawFrames() = runBlocking {
        val mic = FakeMic()
        val sharing = MicFrameSharing(mic.frames, scope, 16)

        val frames = withTimeout(5_000) { sharing.raw.take(3).toList() }

        assertTrue(frames.all { it[0] > 0 })
        waitUntil("the microphone is released") { mic.open == 0 }
        assertEquals(1, mic.opened.get())
    }

    @Test
    fun theProcessedFlowCarriesTheProcessedFrames() = runBlocking {
        val mic = FakeMic()
        val sharing = MicFrameSharing(mic.frames, scope, 16)

        val frame = withTimeout(5_000) { sharing.processed.first() }

        assertTrue(frame[0] < 0)
    }

    @Test
    fun bothFlowsShareOneMicrophoneWhichIsReleasedAfterTheLastSubscriberOfEither() = runBlocking {
        val mic = FakeMic()
        val sharing = MicFrameSharing(mic.frames, scope, 16)

        val wakeWord = launch(start = CoroutineStart.UNDISPATCHED) { sharing.processed.collect {} }
        waitUntil("the microphone opens") { mic.open == 1 }
        val recorder = launch(start = CoroutineStart.UNDISPATCHED) { sharing.raw.collect {} }
        delay(50)
        assertEquals("one microphone for both", 1, mic.opened.get())

        // The wake word stops (say, Home Assistant disconnected): the recorder keeps it open.
        wakeWord.cancel()
        delay(100)
        assertEquals(1, mic.open)
        assertEquals(1, mic.opened.get())

        // Nothing listens any more: released.
        recorder.cancel()
        waitUntil("the microphone is released") { mic.open == 0 }

        // And opened again for the next subscriber.
        withTimeout(5_000) { sharing.raw.first() }
        assertEquals(2, mic.opened.get())
    }

    @Test
    fun rawAndProcessedFramesArriveInStepAndInOrder() = runBlocking {
        val mic = FakeMic()
        val sharing = MicFrameSharing(mic.frames, scope, 64)

        val raw = mutableListOf<Int>()
        val processed = mutableListOf<Int>()
        val a = launch(start = CoroutineStart.UNDISPATCHED) { sharing.raw.collect { synchronized(raw) { raw += it[0].toInt() } } }
        val b = launch(start = CoroutineStart.UNDISPATCHED) { sharing.processed.collect { synchronized(processed) { processed += -it[0].toInt() } } }
        waitUntil("frames arrive") { synchronized(raw) { raw.size } >= 10 && synchronized(processed) { processed.size } >= 10 }
        a.cancel(); b.cancel()

        for (list in listOf(raw, processed)) {
            synchronized(list) { assertEquals(list.sorted(), list) }
        }
    }
}
