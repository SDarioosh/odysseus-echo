package com.msp1974.vacompanion.odysseus.record

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The durable queue and the upload pass, against a fake hub. Fictional data only. */
class RecordingQueueTest {

    private lateinit var root: File
    private lateinit var queue: ChunkQueue

    @Before
    fun setUp() {
        root = Files.createTempDirectory("rec-queue").toFile()
        queue = ChunkQueue(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A hub that answers from a script per path, and records what it was sent. */
    private class FakeHub(val answer: (String, Int) -> RelayReply) : RelayTransport {
        val sent = mutableListOf<Pair<String, String>>()   // path, body text
        override fun post(path: String, contentType: String, body: ByteArray): RelayReply {
            sent += path to String(body, Charsets.ISO_8859_1)
            return answer(path, sent.size)
        }
        fun paths() = sent.map { it.first }
    }

    private val ok = RelayReply(200, """{"ok": true}""")

    private fun session(id: String, kind: String = "recording", created: Long = 1_000L, chunks: Int = 2, finish: Boolean = true): File {
        val dir = queue.createSession(SessionInfo(id, kind, "echo-test", created))
        repeat(chunks) { seq ->
            queue.writeChunk(dir, ChunkInfo(id, seq, kind, ChunkAssembler.isoUtc(created + seq * 30_000L)), ByteArray(100) { seq.toByte() })
        }
        if (finish) queue.finishSession(dir, chunks)
        return dir
    }

    @Test
    fun chunksGoInOrderThenTheEnd() {
        session("aaaaaaaaaaaaaaaa", chunks = 3)
        val hub = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, hub).runPass())
        assertEquals(
            listOf(
                "/api/codex/athena/chunk", "/api/codex/athena/chunk", "/api/codex/athena/chunk",
                "/api/codex/athena/sessions/aaaaaaaaaaaaaaaa/end",
            ),
            hub.paths(),
        )
        val sequences = hub.sent.take(3).map { Regex("name=\"sequence\"\r\n\r\n(\\d+)").find(it.second)!!.groupValues[1] }
        assertEquals(listOf("0", "1", "2"), sequences)
        assertEquals(0, root.listFiles()!!.size)   // delivered and ended: nothing left
    }

    @Test
    fun olderSessionsGoFirst() {
        session("bbbbbbbbbbbbbbbb", created = 2_000L, chunks = 1)
        session("aaaaaaaaaaaaaaaa", created = 1_000L, chunks = 1)
        val hub = FakeHub { _, _ -> ok }
        RecordingUploader(queue, hub).runPass()
        assertTrue(hub.sent[0].second.contains("aaaaaaaaaaaaaaaa"))
        assertEquals("/api/codex/athena/sessions/aaaaaaaaaaaaaaaa/end", hub.paths()[1])
        assertEquals("/api/codex/athena/sessions/bbbbbbbbbbbbbbbb/end", hub.paths()[3])
    }

    @Test
    fun chunkFormFieldsFollowTheContract() {
        session("cccccccccccccccc", kind = "note", created = 1_762_420_500_000L, chunks = 1)
        val hub = FakeHub { _, _ -> ok }
        RecordingUploader(queue, hub).runPass()
        val body = hub.sent[0].second
        fun field(name: String) = Regex("name=\"$name\"\r\n\r\n([^\r]*)").find(body)?.groupValues?.get(1)
        assertEquals("echo-test", field("device"))
        assertEquals("cccccccccccccccc", field("session_id"))
        assertEquals("0", field("sequence"))
        assertEquals("2025-11-06T09:15:00.000Z", field("timestamp"))
        assertEquals("wav", field("codec"))
        assertEquals("note", field("kind"))
        assertTrue(body.contains("name=\"audio\"; filename=\"chunk.wav\""))
    }

    @Test
    fun aRecordingHasNoKindField() {
        session("dddddddddddddddd", kind = "recording", chunks = 1)
        val hub = FakeHub { _, _ -> ok }
        RecordingUploader(queue, hub).runPass()
        assertFalse(hub.sent[0].second.contains("name=\"kind\""))
    }

    @Test
    fun aRetryStopsThePassAndKeepsTheChunk() {
        session("aaaaaaaaaaaaaaaa", chunks = 2)
        for (reply in listOf(RelayReply(503, null), RelayReply(null, null), RelayReply(429, null), RelayReply(200, "{}"))) {
            val hub = FakeHub { _, _ -> reply }
            assertEquals(PassResult.RETRY, RecordingUploader(queue, hub).runPass())
            assertEquals(1, hub.sent.size)
            assertEquals(2, queue.status().chunksWaiting)
        }
        // Later, the hub is back: the same chunk goes first.
        val hub = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, hub).runPass())
        assertTrue(hub.sent[0].second.contains("name=\"sequence\"\r\n\r\n0"))
    }

    @Test
    fun aRefusedTokenStopsUploads() {
        session("aaaaaaaaaaaaaaaa", chunks = 2)
        for (code in listOf(401, 403)) {
            val hub = FakeHub { _, _ -> RelayReply(code, null) }
            assertEquals(PassResult.AUTH, RecordingUploader(queue, hub).runPass())
            assertEquals(1, hub.sent.size)
            assertEquals(2, queue.status().chunksWaiting)
        }
    }

    @Test
    fun aRejectedChunkIsKeptAndSkipped() {
        session("aaaaaaaaaaaaaaaa", chunks = 3)
        val hub = FakeHub { path, n -> if (path.endsWith("/chunk") && n == 2) RelayReply(400, null) else ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, hub).runPass())
        assertEquals(4, hub.sent.size)   // three chunks, then the end
        val status = queue.status()
        assertEquals(0, status.chunksWaiting)
        assertEquals(1, status.rejected)
        val dir = root.listFiles()!!.single()
        assertTrue(File(dir, "000001.wav.rejected").exists())
        assertTrue(File(dir, ChunkQueue.ENDED_FILE).exists())
        // Nothing more is sent for it.
        val again = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, again).runPass())
        assertEquals(0, again.sent.size)
    }

    @Test
    fun aSessionStillRecordingSendsItsChunksButNoEnd() {
        val dir = session("aaaaaaaaaaaaaaaa", chunks = 1, finish = false)
        val hub = FakeHub { _, _ -> ok }
        RecordingUploader(queue, hub).runPass()
        assertEquals(listOf("/api/codex/athena/chunk"), hub.paths())
        queue.finishSession(dir, 1)
        RecordingUploader(queue, hub).runPass()
        assertEquals("/api/codex/athena/sessions/aaaaaaaaaaaaaaaa/end", hub.paths().last())
    }

    @Test
    fun aSessionWithNoAudioIsDroppedWithoutAnEnd() {
        session("aaaaaaaaaaaaaaaa", chunks = 0)
        val hub = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, hub).runPass())
        assertEquals(0, hub.sent.size)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun a404IsTriedOnceOnTheOlderRelayName() {
        session("aaaaaaaaaaaaaaaa", chunks = 2)
        val hub = FakeHub { path, _ -> if (path.startsWith("/api/codex/athena/")) RelayReply(404, null) else ok }
        val uploader = RecordingUploader(queue, hub)
        assertEquals(PassResult.DONE, uploader.runPass())
        // The first chunk tries both; after that the older name is used directly.
        assertEquals(
            listOf(
                "/api/codex/athena/chunk", "/api/codex/sound/chunk",
                "/api/codex/sound/chunk",
                "/api/codex/sound/sessions/aaaaaaaaaaaaaaaa/end",
            ),
            hub.paths(),
        )
        assertEquals(UploadRules.SOUND_PREFIX, uploader.prefix)
    }

    @Test
    fun a404OnBothNamesIsAMissingRelayNotARejectedChunk() {
        session("aaaaaaaaaaaaaaaa", chunks = 2)
        val hub = FakeHub { path, _ -> if (path.endsWith("/chunk")) RelayReply(404, null) else ok }
        val uploader = RecordingUploader(queue, hub)
        assertEquals(PassResult.RELAY_MISSING, uploader.runPass())
        assertEquals(listOf("/api/codex/athena/chunk", "/api/codex/sound/chunk"), hub.paths())
        val status = queue.status()
        assertEquals(0, status.rejected)
        assertEquals(2, status.chunksWaiting)
        assertEquals(UploadRules.ATHENA_PREFIX, uploader.prefix)
        // The hub gets its relay back: the same chunks go, in order.
        val fixed = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, fixed).runPass())
        assertTrue(fixed.sent[0].second.contains("name=\"sequence\"\r\n\r\n0"))
        assertEquals(0, queue.status().chunksWaiting)
    }

    @Test
    fun a404OnBothNamesForAnEndStillEndsTheSession() {
        session("aaaaaaaaaaaaaaaa", chunks = 1)
        val hub = FakeHub { path, _ -> if (path.endsWith("/end")) RelayReply(404, """{"detail": "session not found"}""") else ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, hub).runPass())
        assertEquals(0, queue.status().chunksWaiting)
        // The end was refused, as before: nothing more is sent for the session.
        val again = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, again).runPass())
        assertEquals(0, again.sent.size)
    }

    @Test
    fun sessionsLeftByAnEarlierRunAreFinishedAndSent() {
        session("aaaaaaaaaaaaaaaa", chunks = 3, finish = false)
        assertEquals(1, queue.finishAbandoned())
        assertEquals(0, queue.finishAbandoned())
        val hub = FakeHub { _, _ -> ok }
        RecordingUploader(queue, hub).runPass()
        assertEquals("/api/codex/athena/sessions/aaaaaaaaaaaaaaaa/end", hub.paths().last())
        assertEquals(4, hub.sent.size)
    }

    @Test
    fun theActiveSessionIsNotFinishedAtStart() {
        val dir = session("aaaaaaaaaaaaaaaa", chunks = 1, finish = false)
        assertEquals(0, queue.finishAbandoned(setOf(dir)))
        assertFalse(File(dir, ChunkQueue.END_FILE).exists())
    }

    @Test
    fun aSessionKilledAfterAllItsChunksWereSentStillGetsItsEnd() {
        // Chunks 0 and 1 were uploaded (files deleted), then the app was killed before chunk 2.
        session("aaaaaaaaaaaaaaaa", chunks = 2, finish = false)
        val hub = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, hub).runPass())
        assertEquals(2, hub.sent.size)
        assertEquals(1, queue.finishAbandoned())
        RecordingUploader(queue, hub).runPass()
        assertEquals("/api/codex/athena/sessions/aaaaaaaaaaaaaaaa/end", hub.paths().last())
        assertEquals(3, hub.sent.size)
        assertNull(queue.next())
    }

    @Test
    fun anUnreadableSidecarIsRebuiltNotStuck() {
        // Power lost after the rename but before the data reached the disk: an empty sidecar.
        val dir = session("aaaaaaaaaaaaaaaa", created = 5_000L, chunks = 2)
        File(dir, "000001.json").writeBytes(ByteArray(0))
        val hub = FakeHub { _, _ -> ok }
        assertEquals(PassResult.DONE, RecordingUploader(queue, hub).runPass())
        assertEquals(3, hub.sent.size)
        assertTrue(hub.sent[1].second.contains("1970-01-01T00:00:35.000Z"))
        assertTrue(hub.sent[1].second.contains("name=\"sequence\"\r\n\r\n1\r\n"))
        assertEquals("/api/codex/athena/sessions/aaaaaaaaaaaaaaaa/end", hub.paths().last())
    }

    @Test
    fun aHalfWrittenChunkIsNotReady() {
        val dir = session("aaaaaaaaaaaaaaaa", chunks = 0, finish = false)
        File(dir, "000000.wav.tmp").writeBytes(ByteArray(10))
        assertNull(queue.next())
        assertEquals(0, queue.status().chunksWaiting)
    }

    @Test
    fun theCapDropsTheOldestFinishedSessions() {
        session("aaaaaaaaaaaaaaaa", created = 1_000L, chunks = 2)
        session("bbbbbbbbbbbbbbbb", created = 2_000L, chunks = 2)
        val live = session("cccccccccccccccc", created = 3_000L, chunks = 2, finish = false)
        val perSession = queue.status().bytes / 3
        assertEquals(1, queue.enforceCap(maxBytes = perSession * 2 + 10))
        val left = root.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("2000-bbbbbbbbbbbbbbbb", live.name), left)
    }

    @Test
    fun statusCountsWaitingChunks() {
        session("aaaaaaaaaaaaaaaa", chunks = 3)
        assertEquals(3, queue.status().chunksWaiting)
        assertEquals("3 chunks waiting (0.0 MB)", RecordingText.queue(queue.status()))
    }
}
