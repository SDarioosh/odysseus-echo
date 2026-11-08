package com.msp1974.vacompanion.odysseus.wakeword

import com.msp1974.vacompanion.odysseus.HubCall
import com.msp1974.vacompanion.odysseus.HubHttp
import com.msp1974.vacompanion.odysseus.HubReply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class WakeClipQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun clip(at: Long, kind: WakeClip.Kind = WakeClip.Kind.GUIDED) =
        WakeClip(kind, ShortArray(16_000) { 100 }, 0.5f, 0.92f, at, "Quietly")

    /** A fake hub: answers [replies] in order (null: unreachable), then {"ok": true}. */
    private class FakeHub(val replies: ArrayDeque<HubReply?> = ArrayDeque()) : HubHttp {
        val bodies = mutableListOf<String>()
        val paths = mutableListOf<String>()
        override suspend fun request(call: HubCall): HubReply {
            assertTrue(call.contentType!!.startsWith("multipart/form-data; boundary="))
            paths += call.path
            bodies += String(call.body!!, Charsets.ISO_8859_1)
            if (replies.isEmpty()) return HubReply(200, """{"ok": true, "id": "x", "already": false}""")
            return replies.removeFirst() ?: throw IOException("connection refused")
        }
    }

    private fun upload(q: WakeClipQueue, hub: HubHttp, onSent: () -> Unit = {}) =
        runBlocking { q.upload("dev1", "Kitchen Echo", hub, onSent) }

    @Test
    fun uploadsOldestFirstWithTheContractFields() {
        val q = WakeClipQueue(tmp.root)
        q.add(clip(2_000, WakeClip.Kind.MISSED), "hey_athena")
        q.add(clip(1_000), "hey_athena")
        assertEquals(2, q.size())
        val hub = FakeHub()
        var sent = 0
        assertEquals(UploadResult(ClipPass.DONE), upload(q, hub) { sent++ })
        assertEquals(2, sent)
        assertEquals(0, q.size())
        assertEquals(listOf("/satellite/wakeword/clip", "/satellite/wakeword/clip"), hub.paths)
        val first = hub.bodies[0]
        for (field in listOf("clip_id", "device_id", "device_name", "wake_word", "kind", "score", "threshold", "prompt", "recorded_at")) {
            assertTrue(field, first.contains("name=\"$field\""))
        }
        assertTrue(first.contains("\r\n1000\r\n"))
        assertTrue(first.contains("\r\nguided\r\n"))
        assertTrue(first.contains("\r\n0.920\r\n"))
        assertTrue(first.contains("\r\nKitchen Echo\r\n"))
        assertTrue(first.contains("name=\"audio\"; filename=\"clip.wav\""))
        assertTrue(first.contains("Content-Type: audio/wav"))
        assertTrue(first.contains("RIFF"))
        assertTrue(hub.bodies[1].contains("\r\nmissed\r\n"))
    }

    @Test
    fun aFailureStopsThePassAndKeepsTheClip() {
        val q = WakeClipQueue(tmp.root)
        q.add(clip(1_000), "hey_athena")
        assertEquals(UploadResult(ClipPass.RETRY, "connection refused"), upload(q, FakeHub(ArrayDeque(listOf(null)))))
        assertEquals(UploadResult(ClipPass.AUTH, "Token refused (401)"), upload(q, FakeHub(ArrayDeque(listOf(HubReply(401, ""))))))
        assertEquals(UploadResult(ClipPass.RETRY, "HTTP 404"), upload(q, FakeHub(ArrayDeque(listOf(HubReply(404, ""))))))
        assertEquals(UploadResult(ClipPass.RETRY, "HTTP 507"), upload(q, FakeHub(ArrayDeque(listOf(HubReply(507, ""))))))
        assertEquals(1, q.size())
    }

    @Test
    fun aRefusedClipIsDropped() {
        val q = WakeClipQueue(tmp.root)
        q.add(clip(1_000), "hey_athena")
        assertEquals(ClipPass.DONE, upload(q, FakeHub(ArrayDeque(listOf(HubReply(422, ""))))).pass)
        assertEquals(0, q.size())
    }

    @Test
    fun aFullQueueDropsTheOldest() {
        val q = WakeClipQueue(tmp.root, maxClips = 3)
        for (i in 1..5) q.add(clip(i * 1_000L), "hey_athena")
        assertEquals(3, q.size())
        val hub = FakeHub()
        upload(q, hub)
        assertTrue(hub.bodies[0].contains("\r\n3000\r\n"))
    }

    @Test
    fun classifiesAnswers() {
        fun c(code: Int, body: String = "") = WakeClipQueue.classify(HubReply(code, body))
        assertEquals(WakeClipQueue.ClipOutcome.Sent, c(200, """{"ok": true, "id": "x", "already": true}"""))
        assertEquals(WakeClipQueue.ClipOutcome.Retry("unreadable answer"), c(200, "{}"))
        assertEquals(WakeClipQueue.ClipOutcome.Retry("unreadable answer"), c(200, "<html>"))
        assertEquals(WakeClipQueue.ClipOutcome.Auth("Token refused (403)"), c(403))
        assertEquals(WakeClipQueue.ClipOutcome.Retry("HTTP 405"), c(405))
        assertEquals(WakeClipQueue.ClipOutcome.Retry("HTTP 429"), c(429))
        assertEquals(WakeClipQueue.ClipOutcome.Refused, c(415))
        assertEquals(WakeClipQueue.ClipOutcome.Retry("HTTP 503"), c(503))
    }
}
