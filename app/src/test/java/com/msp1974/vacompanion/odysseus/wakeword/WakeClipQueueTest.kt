package com.msp1974.vacompanion.odysseus.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakeClipQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun clip(at: Long, kind: WakeClip.Kind = WakeClip.Kind.GUIDED) =
        WakeClip(kind, ShortArray(16_000) { 100 }, 0.5f, 0.92f, at, "Quietly")

    private class Recorder(val replies: ArrayDeque<ClipReply>) : ClipTransport {
        val bodies = mutableListOf<String>()
        override fun post(contentType: String, body: ByteArray): ClipReply {
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            bodies += String(body, Charsets.ISO_8859_1)
            return replies.removeFirstOrNull() ?: ClipReply(200, "{\"ok\": true}")
        }
    }

    @Test
    fun uploadsOldestFirstWithTheContractFields() {
        val q = WakeClipQueue(tmp.root)
        q.add(clip(2_000, WakeClip.Kind.MISSED), "hey_athena")
        q.add(clip(1_000), "hey_athena")
        assertEquals(2, q.size())
        val t = Recorder(ArrayDeque())
        var sent = 0
        assertEquals(ClipPass.DONE, q.upload("dev1", "Kitchen Echo", t) { sent++ })
        assertEquals(2, sent)
        assertEquals(0, q.size())
        val first = t.bodies[0]
        for (field in listOf("clip_id", "device_id", "device_name", "wake_word", "kind", "score", "threshold", "prompt", "recorded_at")) {
            assertTrue(field, first.contains("name=\"$field\""))
        }
        assertTrue(first.contains("\r\n1000\r\n"))
        assertTrue(first.contains("\r\nguided\r\n"))
        assertTrue(first.contains("\r\n0.920\r\n"))
        assertTrue(first.contains("filename=\"clip.wav\""))
        assertTrue(first.contains("RIFF"))
        assertTrue(t.bodies[1].contains("\r\nmissed\r\n"))
    }

    @Test
    fun aFailureStopsThePassAndKeepsTheClip() {
        val q = WakeClipQueue(tmp.root)
        q.add(clip(1_000), "hey_athena")
        assertEquals(ClipPass.RETRY, q.upload("d", "", Recorder(ArrayDeque(listOf(ClipReply(null, null))))))
        assertEquals(ClipPass.AUTH, q.upload("d", "", Recorder(ArrayDeque(listOf(ClipReply(401, null))))))
        assertEquals(ClipPass.NO_ROUTE, q.upload("d", "", Recorder(ArrayDeque(listOf(ClipReply(404, null))))))
        assertEquals(ClipPass.RETRY, q.upload("d", "", Recorder(ArrayDeque(listOf(ClipReply(507, null))))))
        assertEquals(1, q.size())
    }

    @Test
    fun aRefusedClipIsDropped() {
        val q = WakeClipQueue(tmp.root)
        q.add(clip(1_000), "hey_athena")
        assertEquals(ClipPass.DONE, q.upload("d", "", Recorder(ArrayDeque(listOf(ClipReply(422, null))))))
        assertEquals(0, q.size())
    }

    @Test
    fun aFullQueueDropsTheOldest() {
        val q = WakeClipQueue(tmp.root, maxClips = 3)
        for (i in 1..5) q.add(clip(i * 1_000L), "hey_athena")
        assertEquals(3, q.size())
        val t = Recorder(ArrayDeque())
        q.upload("d", "", t)
        assertTrue(t.bodies[0].contains("\r\n3000\r\n"))
    }

    @Test
    fun classifiesAnswers() {
        assertEquals(WakeClipQueue.ClipOutcome.SENT, WakeClipQueue.classify(ClipReply(200, "{\"ok\": true, \"already\": true}")))
        assertEquals(WakeClipQueue.ClipOutcome.RETRY, WakeClipQueue.classify(ClipReply(200, "{}")))
        assertEquals(WakeClipQueue.ClipOutcome.AUTH, WakeClipQueue.classify(ClipReply(403, null)))
        assertEquals(WakeClipQueue.ClipOutcome.NO_ROUTE, WakeClipQueue.classify(ClipReply(405, null)))
        assertEquals(WakeClipQueue.ClipOutcome.REFUSED, WakeClipQueue.classify(ClipReply(415, null)))
        assertEquals(WakeClipQueue.ClipOutcome.RETRY, WakeClipQueue.classify(ClipReply(503, null)))
    }
}
