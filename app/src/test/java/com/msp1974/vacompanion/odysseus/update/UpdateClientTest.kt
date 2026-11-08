package com.msp1974.vacompanion.odysseus.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class UpdateClientTest {
    @get:Rule val tmp = TemporaryFolder()

    // Fictional bytes, not a real APK.
    private val apk = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val release = AppRelease("odysseus-echo", "0.14.0+odysseus.30", 1400030, apk.size.toLong(), sha, null)

    /** A fake hub: [latest] answers /app/latest; the APK honours Range unless [ignoreRange]. */
    private class FakeHub(
        val latest: UpdateResponse? = null,
        val apk: ByteArray = ByteArray(0),
        val apkCode: Int = 200,
        val ignoreRange: Boolean = false,
        val cutAfter: Int = Int.MAX_VALUE,
        /** Answer a range request with a 206 from this byte instead of the one asked for. */
        val rangeAnsweredFrom: Long? = null,
    ) : UpdateHttp {
        val ranges = mutableListOf<Long>()
        val paths = mutableListOf<String>()
        override fun getText(path: String): UpdateResponse {
            paths += path
            return latest ?: throw IOException("connection refused")
        }

        override fun getStream(path: String, rangeFrom: Long): UpdateResponse {
            paths += path
            ranges += rangeFrom
            if (apkCode !in 200..299) return UpdateResponse(apkCode)
            val from = (if (ignoreRange) 0L else rangeAnsweredFrom?.takeIf { rangeFrom > 0 } ?: rangeFrom).toInt()
            val bytes = apk.copyOfRange(from, apk.size)
            val code = if (from > 0 || (rangeFrom > 0 && !ignoreRange)) 206 else 200
            val range = if (code == 206) "bytes $from-${apk.size - 1}/${apk.size}" else null
            return UpdateResponse(code, contentLength = bytes.size.toLong(), body = CutStream(bytes, cutAfter), contentRange = range)
        }
    }

    /** Fails with an IOException after [cutAfter] bytes, like a dropped connection. */
    private class CutStream(bytes: ByteArray, private val cutAfter: Int) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        private var read = 0
        override fun read(): Int {
            if (read >= cutAfter) throw IOException("connection reset")
            return inner.read().also { if (it >= 0) read++ }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= cutAfter) throw IOException("connection reset")
            val n = inner.read(b, off, minOf(len, cutAfter - read))
            if (n > 0) read += n
            return n
        }
    }

    @Test
    fun latestParsesTheRelease() {
        val hub = FakeHub(UpdateResponse(200, """{"app":"odysseus-echo","version_name":"0.14.0+odysseus.30","version_code":1400030,"size":200000,"sha256":"$sha","notes":"n"}"""))
        val r = UpdateClient(hub).latest()
        assertEquals(CheckResult.Found(release.copy(notes = "n")), r)
        assertEquals(listOf("/app/latest"), hub.paths)
    }

    @Test
    fun latestNoReleaseAndFailures() {
        assertEquals(CheckResult.NoRelease, UpdateClient(FakeHub(UpdateResponse(404, """{"error": "no release"}"""))).latest())
        assertEquals(CheckResult.Failed("Token refused (403)"), UpdateClient(FakeHub(UpdateResponse(403))).latest())
        assertEquals(CheckResult.Failed("HTTP 500"), UpdateClient(FakeHub(UpdateResponse(500))).latest())
        assertEquals(CheckResult.Failed("connection refused"), UpdateClient(FakeHub(null)).latest())
        assertTrue(UpdateClient(FakeHub(UpdateResponse(200, "<html>"))).latest() is CheckResult.Failed)
        val wrongApp = """{"app":"other","version_code":2,"size":1,"sha256":"$sha"}"""
        assertTrue(UpdateClient(FakeHub(UpdateResponse(200, wrongApp))).latest() is CheckResult.Failed)
    }

    @Test
    fun downloadsAndVerifies() {
        val dir = tmp.newFolder("upd")
        File(dir, "1400029-old.apk").writeText("old")
        val hub = FakeHub(apk = apk)
        val r = UpdateClient(hub).download(release, dir)
        assertTrue(r is DownloadResult.Ok)
        val file = (r as DownloadResult.Ok).file
        assertArrayEquals(apk, file.readBytes())
        assertEquals("1400030-$sha.apk", file.name)
        assertEquals(listOf(file.name), dir.list()!!.toList())
        assertEquals(listOf(0L), hub.ranges)
    }

    @Test
    fun aVerifiedFileIsNotDownloadedAgain() {
        val dir = tmp.newFolder("upd")
        File(dir, "1400030-$sha.apk").writeBytes(apk)
        val hub = FakeHub(apk = apk)
        assertTrue(UpdateClient(hub).download(release, dir) is DownloadResult.Ok)
        assertTrue(hub.ranges.isEmpty())
    }

    @Test
    fun resumesACutOffDownload() {
        val dir = tmp.newFolder("upd")
        val first = UpdateClient(FakeHub(apk = apk, cutAfter = 70_000)).download(release, dir)
        assertTrue(first is DownloadResult.Failed)
        val part = File(dir, "1400030-$sha.apk.part")
        assertTrue(part.exists())
        assertEquals(70_000L, part.length())

        val hub = FakeHub(apk = apk)
        val second = UpdateClient(hub).download(release, dir)
        assertTrue(second is DownloadResult.Ok)
        assertEquals(listOf(70_000L), hub.ranges)
        assertArrayEquals(apk, (second as DownloadResult.Ok).file.readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun startsOverWhenTheHubIgnoresTheRange() {
        val dir = tmp.newFolder("upd")
        File(dir, "1400030-$sha.apk.part").writeBytes(apk.copyOfRange(0, 50_000))
        val hub = FakeHub(apk = apk, ignoreRange = true)
        val r = UpdateClient(hub).download(release, dir)
        assertTrue(r is DownloadResult.Ok)
        assertEquals(listOf(50_000L), hub.ranges)
        assertArrayEquals(apk, (r as DownloadResult.Ok).file.readBytes())
    }

    @Test
    fun aRangeAnsweredFromAnotherByteIsNotAppended() {
        val dir = tmp.newFolder("upd")
        val part = File(dir, "1400030-$sha.apk.part")
        part.writeBytes(apk.copyOfRange(0, 50_000))
        // Bytes from 40 000 appended at 50 000 would only be caught by the sha256 (and then the
        // version waits a day): refuse them at once and start over next time.
        val r = UpdateClient(FakeHub(apk = apk, rangeAnsweredFrom = 40_000)).download(release, dir)
        assertTrue(r is DownloadResult.Failed)
        assertTrue((r as DownloadResult.Failed).message.contains("40000"))
        assertFalse(part.exists())
    }

    @Test
    fun aRangeAnsweredFromTheStartIsAWholeFile() {
        val dir = tmp.newFolder("upd")
        File(dir, "1400030-$sha.apk.part").writeBytes(apk.copyOfRange(0, 50_000))
        val r = UpdateClient(FakeHub(apk = apk, rangeAnsweredFrom = 0)).download(release, dir)
        assertTrue(r is DownloadResult.Ok)
        assertArrayEquals(apk, (r as DownloadResult.Ok).file.readBytes())
    }

    @Test
    fun contentRangeStart() {
        assertEquals(1000L, UpdateClient.rangeStart("bytes 1000-1999/2000"))
        assertEquals(0L, UpdateClient.rangeStart("Bytes 0-9/*"))
        assertEquals(null, UpdateClient.rangeStart(null))
        assertEquals(null, UpdateClient.rangeStart("bytes */2000"))
    }

    @Test
    fun deletesADownloadThatDoesNotMatch() {
        val dir = tmp.newFolder("upd")
        val bad = apk.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val r = UpdateClient(FakeHub(apk = bad)).download(release, dir)
        assertTrue(r is DownloadResult.Failed)
        assertTrue((r as DownloadResult.Failed).message.contains("sha256"))
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun refusesADownloadLargerThanAnnounced() {
        val dir = tmp.newFolder("upd")
        val r = UpdateClient(FakeHub(apk = apk + ByteArray(10))).download(release, dir)
        assertTrue(r is DownloadResult.Failed)
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun reportsHttpErrors() {
        val dir = tmp.newFolder("upd")
        assertEquals(DownloadResult.Failed("Token refused (401)"), UpdateClient(FakeHub(apkCode = 401)).download(release, dir))
        assertEquals(DownloadResult.Failed("the hub has no APK (404)"), UpdateClient(FakeHub(apkCode = 404)).download(release, dir))
    }
}
