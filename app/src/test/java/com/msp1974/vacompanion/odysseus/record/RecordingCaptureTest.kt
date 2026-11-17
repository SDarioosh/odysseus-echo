package com.msp1974.vacompanion.odysseus.record

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier

/** One capture against a fake microphone and a real queue in a temporary folder. */
class RecordingCaptureTest {

    private val root: File = Files.createTempDirectory("rec-capture").toFile()
    private val queue = ChunkQueue(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun capture(mic: MutableSharedFlow<ShortArray>, id: String, finished: CountDownLatch? = null): RecordingCapture {
        val dir = queue.createSession(SessionInfo(id, "recording", "echo-test", 1_000L))
        return object : RecordingCapture(RecordingKind.RECORDING, id, 1_000L, dir, queue, mic, scope, Dispatchers.IO) {
            override fun onChunkWritten(sequence: Int) {}
            override fun onFinished(chunks: Int) { finished?.countDown() }
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
    fun whatWasHeardIsWrittenAndTheSessionFinished() = runBlocking {
        val mic = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
        val done = CountDownLatch(1)
        val c = capture(mic, "aaaaaaaaaaaaaaaa", done)
        assertEquals(1, mic.subscriptionCount.value)   // listening when the constructor returns
        repeat(3) { mic.emit(ShortArray(1280) { 7 }) }
        waitUntil("the frames reached the capture") { c.lastFrameAtMs > 1_000L }
        delay(50)
        c.finish()
        done.await()
        val item = queue.next() as QueueItem.Chunk
        assertEquals(44 + 3 * 1280 * 2, item.wav.length().toInt())
        waitUntil("the microphone was let go") { mic.subscriptionCount.value == 0 }
    }

    @Test
    fun aResubscribeRacingFinishNeverLeavesTheMicrophoneOpen() = runBlocking {
        // The ticker's resubscribe() runs outside the recorder's lock, at the same time as a stop.
        val mic = MutableSharedFlow<ShortArray>()
        repeat(300) { i ->
            val c = capture(mic, "%016x".format(i))
            val barrier = CyclicBarrier(2)
            val a = Thread { barrier.await(); c.resubscribe() }
            val b = Thread { barrier.await(); c.finish() }
            a.start(); b.start(); a.join(); b.join()
        }
        waitUntil("every listener has left (${mic.subscriptionCount.value} left)") { mic.subscriptionCount.value == 0 }
    }
}
