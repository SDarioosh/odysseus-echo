package com.msp1974.vacompanion.odysseus.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRulesTest {

    // Contract C4's result table.

    @Test
    fun okTrueIsOk() {
        assertEquals(UploadClass.OK, UploadRules.classify(200, """{"ok": true}"""))
        assertEquals(UploadClass.OK, UploadRules.classify(200, """{"ok":true,"stop":false,"reason":null}"""))
    }

    @Test
    fun aDuplicateCountsAsOk() {
        assertEquals(UploadClass.OK, UploadRules.classify(200, """{"ok": true, "duplicate": true}"""))
    }

    @Test
    fun serverErrorsTimeoutsAndRateLimitsAreRetried() {
        for (code in listOf(500, 502, 503, 504, 408, 429)) {
            assertEquals("HTTP $code", UploadClass.RETRY, UploadRules.classify(code, null))
        }
    }

    @Test
    fun aNetworkErrorIsRetried() {
        assertEquals(UploadClass.RETRY, UploadRules.classify(null, null))
    }

    @Test
    fun a200WithoutOkIsRetried() {
        assertEquals(UploadClass.RETRY, UploadRules.classify(200, """{"ok": false}"""))
        assertEquals(UploadClass.RETRY, UploadRules.classify(200, ""))
        assertEquals(UploadClass.RETRY, UploadRules.classify(200, null))
        assertEquals(UploadClass.RETRY, UploadRules.classify(200, "<html>proxy</html>"))
    }

    @Test
    fun unauthorisedAndForbiddenStopUploads() {
        assertEquals(UploadClass.AUTH, UploadRules.classify(401, null))
        assertEquals(UploadClass.AUTH, UploadRules.classify(403, null))
    }

    @Test
    fun otherClientErrorsRejectTheChunk() {
        for (code in listOf(400, 404, 409, 413, 415, 422)) {
            assertEquals("HTTP $code", UploadClass.REJECTED, UploadRules.classify(code, null))
        }
    }

    @Test
    fun retriesWait1_2_5_10_thenEvery15Minutes() {
        val minutes = (1..7).map { UploadRules.retryDelayMs(it) / 60_000 }
        assertEquals(listOf(1L, 2L, 5L, 10L, 15L, 15L, 15L), minutes)
        assertEquals(60_000L, UploadRules.retryDelayMs(0))
    }

    @Test
    fun hubOriginComesFromTheVoiceBaseUrl() {
        assertEquals("https://hub.example:7000", UploadRules.hubOrigin("https://hub.example:7000/api/chat/voice/v1"))
        assertEquals("http://192.168.1.20:7000", UploadRules.hubOrigin("http://192.168.1.20:7000/api/chat/voice/v1/"))
        assertEquals("https://hub.example/odysseus", UploadRules.hubOrigin("https://hub.example/odysseus/api/chat/voice/v1"))
        assertEquals("https://hub.example", UploadRules.hubOrigin("https://hub.example"))
        assertNull(UploadRules.hubOrigin(""))
        assertNull(UploadRules.hubOrigin("hub.example/api/chat/voice/v1"))
    }

    @Test
    fun relayPaths() {
        assertEquals("/api/codex/athena/chunk", UploadRules.chunkPath(UploadRules.ATHENA_PREFIX))
        assertEquals("/api/codex/sound/sessions/0123456789abcdef/end", UploadRules.endPath(UploadRules.SOUND_PREFIX, "0123456789abcdef"))
    }

    @Test
    fun sessionIdsAre16LowercaseHexDigits() {
        val a = UploadRules.newSessionId()
        val b = UploadRules.newSessionId()
        assertTrue(a, Regex("^[0-9a-f]{16}$").matches(a))
        assertNotEquals(a, b)
    }

    @Test
    fun deviceField() {
        assertEquals("echo-kitchen-1", UploadRules.deviceField("kitchen-1"))
        assertEquals("echo-unknown", UploadRules.deviceField(" "))
    }

    // Queue cap: oldest finished sessions go first; one still recording never does.

    @Test
    fun underTheCapNothingIsDropped() {
        val s = listOf(QueuedSession("a", 1, 100, true), QueuedSession("b", 2, 100, true))
        assertEquals(emptyList<String>(), QueueCap.sessionsToDrop(s, maxBytes = 200))
    }

    @Test
    fun overTheCapTheOldestFinishedSessionsAreDropped() {
        val s = listOf(
            QueuedSession("new", 3, 100, true),
            QueuedSession("old", 1, 100, true),
            QueuedSession("mid", 2, 100, true),
        )
        assertEquals(listOf("old", "mid"), QueueCap.sessionsToDrop(s, maxBytes = 150))
    }

    @Test
    fun aSessionStillRecordingIsNeverDropped() {
        val s = listOf(QueuedSession("live", 1, 1000, false), QueuedSession("done", 2, 100, true))
        assertEquals(listOf("done"), QueueCap.sessionsToDrop(s, maxBytes = 500))
    }
}
