package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: talks to the hub for updates (contract C2) and downloads the APK into
// app-private storage with the voice token. Not Android's DownloadManager: it can't send the
// Bearer token safely and would put the file where other apps can read it. HTTP goes through
// UpdateHttp so the client is unit-tested with a fake (UpdateClientTest).
//
//   GET <voice base>/app/latest  -> 200 AppRelease | 404 {"error": "no release"}
//   GET <voice base>/app/apk     -> the file; "Range: bytes=N-" may be answered 206 or 200

import com.msp1974.vacompanion.odysseus.hubStatusText
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One answer from the hub. [body] is null unless the caller asked for it (2xx only). */
class UpdateResponse(
    val code: Int,
    val text: String = "",
    val contentLength: Long = -1,
    val body: InputStream? = null,
    /** The `Content-Range` header of a 206 ("bytes 1000-1999/2000"), or null. */
    val contentRange: String? = null,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        runCatching { body?.close() }
        onClose()
    }
}

interface UpdateHttp {
    /** GET [path] and read a short text body. Throws on network errors. */
    fun getText(path: String): UpdateResponse

    /** GET [path] as a stream, from byte [rangeFrom] when it is > 0. Throws on network errors. */
    fun getStream(path: String, rangeFrom: Long): UpdateResponse
}

sealed interface CheckResult {
    data class Found(val release: AppRelease) : CheckResult
    /** The hub has no release uploaded (404). */
    data object NoRelease : CheckResult
    data class Failed(val message: String) : CheckResult
}

sealed interface DownloadResult {
    data class Ok(val file: File) : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

class UpdateClient(private val http: UpdateHttp) {

    fun latest(): CheckResult = try {
        http.getText(LATEST_PATH).use { r ->
            when (r.code) {
                200 -> {
                    val release = runCatching { AppRelease.parse(r.text) }.getOrNull()
                    val problem = release?.problem() ?: if (release == null) "unreadable answer from the hub" else null
                    if (problem != null) CheckResult.Failed(problem) else CheckResult.Found(release!!)
                }
                404 -> CheckResult.NoRelease
                else -> CheckResult.Failed(hubStatusText(r.code))
            }
        }
    } catch (e: Exception) {
        CheckResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    /**
     * Downloads [release] into [dir] as `<version code>-<sha256>.apk`, resuming a `.part` left
     * by an earlier try, then checks size and sha256. A file that doesn't match is deleted. An
     * APK already there and matching is returned without a download. Other APKs and partial
     * files in [dir] are deleted. Never throws.
     */
    fun download(release: AppRelease, dir: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): DownloadResult {
        try {
            dir.mkdirs()
            val name = fileName(release)
            val done = File(dir, "$name.apk")
            val part = File(dir, "$name.apk.part")
            dir.listFiles()?.forEach { if (it != done && it != part) it.delete() }

            if (done.exists()) {
                if (matches(done, release)) return DownloadResult.Ok(done)
                done.delete()
            }

            var from = UpdatePolicy.resumeFrom(if (part.exists()) part.length() else 0L, release.size)
            if (from == 0L) part.delete()
            http.getStream(APK_PATH, from).use { r ->
                when (r.code) {
                    206 -> when (val start = rangeStart(r.contentRange)) {
                        null, from -> Unit
                        // Answered from the start after all: the same as a 200.
                        0L -> { from = 0; part.delete() }
                        else -> {
                            part.delete()
                            return DownloadResult.Failed("the hub answered bytes from $start, not $from")
                        }
                    }
                    200 -> if (from > 0) { from = 0; part.delete() } // the hub ignored the range
                    404 -> return DownloadResult.Failed("the hub has no APK (404)")
                    else -> return DownloadResult.Failed(hubStatusText(r.code))
                }
                val body = r.body ?: return DownloadResult.Failed("empty answer from the hub")
                var have = from
                java.io.FileOutputStream(part, from > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = body.read(buf)
                        if (n < 0) break
                        have += n
                        if (have > release.size) {
                            part.delete()
                            return DownloadResult.Failed("the download is larger than the hub said")
                        }
                        out.write(buf, 0, n)
                        onProgress(have, release.size)
                    }
                }
            }

            if (part.length() < release.size) {
                // Cut off: keep the part to resume next time.
                return DownloadResult.Failed("download cut off at ${part.length()} of ${release.size} bytes")
            }
            if (!matches(part, release)) {
                part.delete()
                return DownloadResult.Failed("the download doesn't match the hub's sha256; deleted")
            }
            if (!part.renameTo(done)) return DownloadResult.Failed("couldn't store the download")
            return DownloadResult.Ok(done)
        } catch (e: Exception) {
            return DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val LATEST_PATH = "/app/latest"
        const val APK_PATH = "/app/apk"

        fun fileName(release: AppRelease) = "${release.versionCode}-${release.sha}"

        /** The first byte of a `Content-Range: bytes START-END/TOTAL`, or null when unreadable. */
        fun rangeStart(header: String?): Long? =
            header?.trim()?.let { Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""", RegexOption.IGNORE_CASE).matchEntire(it) }
                ?.groupValues?.get(1)?.toLongOrNull()

        fun matches(file: File, release: AppRelease): Boolean =
            file.length() == release.size && sha256(file) == release.sha

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
