package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: thumbnails for the Library and Search tabs (docs/odysseus/music.md, "Images").
// A memory LRU sized by bytes (~16 MB of decoded bitmaps) over a 200-file disk cache in cacheDir,
// loading the hub's C8 URLs (`<voice base>/music/image?u=…`) with the Odysseus bearer and Home
// Assistant paths (`/api/…`) with HaApi.bytes. Nothing else is fetched: the device never loads
// images from the internet itself. No URL or a failed load shows a placeholder icon by kind. The
// cache rules (ByteLru, DiskCache) are plain Kotlin / java.io, unit-tested (MusicImagesTest).

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.music.MediaItem
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A least-recently-used map limited by the summed [sizeOf] of its values ([maxBytes]); the least
 * recently used go first. A value bigger than [maxBytes] isn't kept. Thread-safe.
 */
class ByteLru<V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<String, V>(16, 0.75f, true)
    private var total = 0L

    @Synchronized fun get(key: String): V? = map[key]

    @Synchronized fun put(key: String, value: V) {
        val size = sizeOf(value)
        map.remove(key)?.let { total -= sizeOf(it) }
        if (size > maxBytes) return
        map[key] = value
        total += size
        val it = map.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val eldest = it.next()
            total -= sizeOf(eldest.value)
            it.remove()
        }
    }

    @Synchronized fun bytes(): Long = total
    @Synchronized fun keys(): List<String> = map.keys.toList()
}

/**
 * Raw image bytes on disk in [dir], one file per key (its sha256), about [maxFiles]: the first write
 * and every [trimEvery]th after it delete the least recently used beyond [maxFiles] (by
 * modification time; a read touches the file), so the folder holds at most [maxFiles] + [trimEvery]
 * - 1 files and isn't listed on every write. Blocking: call it off the main thread.
 */
class DiskCache(
    private val dir: File,
    private val maxFiles: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
    private val trimEvery: Int = 20,
) {
    private var writes = 0

    fun fileOf(key: String): File = File(dir, sha256(key))

    fun read(key: String): ByteArray? {
        val f = fileOf(key)
        if (!f.isFile) return null
        return try {
            f.readBytes().also { f.setLastModified(clock()) }
        } catch (e: IOException) {
            null
        }
    }

    @Synchronized fun write(key: String, bytes: ByteArray) {
        try {
            dir.mkdirs()
            val f = fileOf(key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return
            }
            f.setLastModified(clock())
            if (writes++ % trimEvery.coerceAtLeast(1) == 0) trim()
        } catch (e: IOException) {
            Timber.d("Music: image cache write failed: %s", e.message)
        }
    }

    /** Deletes the least recently used files beyond [maxFiles]. */
    @Synchronized fun trim() {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
        if (files.size <= maxFiles) return
        files.sortedWith(compareBy({ it.lastModified() }, { it.name }))
            .take(files.size - maxFiles)
            .forEach { it.delete() }
    }

    fun count(): Int = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.size ?: 0

    companion object {
        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

object MusicImages {
    /** Decoded bitmaps kept in memory. */
    const val MEMORY_BYTES = 16L * 1024 * 1024

    /** Files kept on disk (`cacheDir/music_images/`). */
    const val DISK_FILES = 200

    /** A URL that failed isn't asked again for this long. */
    const val RETRY_MS = 5 * 60_000L

    /** Bigger answers aren't read. */
    private const val MAX_BYTES = 5 * 1024 * 1024

    /** Bigger answers (HA artwork) aren't written to disk. */
    private const val DISK_MAX_BYTES = 1024 * 1024

    val memory = ByteLru<Bitmap>(MEMORY_BYTES) { it.allocationByteCount.toLong() }
    @Volatile private var disk: DiskCache? = null
    private val failed = HashMap<String, Long>()
    private val loads = Semaphore(4)

    /** Sets up the disk cache (once; the composables call it). */
    fun init(context: Context) {
        if (disk != null) return
        synchronized(this) {
            if (disk == null) disk = DiskCache(File(context.applicationContext.cacheDir, "music_images"), DISK_FILES)
        }
    }

    /** Thumbnails are decoded to one of these sizes (px, longest side): the smallest that covers the view. */
    fun bucket(px: Int): Int = when {
        px <= 64 -> 64
        px <= 128 -> 128
        px <= 256 -> 256
        else -> 512
    }

    private fun key(url: String, px: Int) = "$px|$url"

    /** The bitmap already in memory, or null. */
    fun cached(url: String, px: Int): Bitmap? = memory.get(key(url, bucket(px)))

    /** Loads [url] decoded to about [px] (memory, then disk, then the network); null when it can't. */
    suspend fun load(url: String, px: Int): Bitmap? {
        val size = bucket(px)
        memory.get(key(url, size))?.let { return it }
        synchronized(failed) {
            val at = failed[url]
            if (at != null && System.currentTimeMillis() - at < RETRY_MS) return null
        }
        return loads.withPermit {
            memory.get(key(url, size))?.let { return@withPermit it }
            try {
                // Disk, hashing and decoding are blocking: never on the main thread.
                val bitmap = withContext(Dispatchers.IO) {
                    val bytes = disk?.read(url) ?: fetch(url)?.also { if (it.size <= DISK_MAX_BYTES) disk?.write(url, it) }
                    bytes?.let { decode(it, size) }
                }
                if (bitmap == null) synchronized(failed) { failed[url] = System.currentTimeMillis() }
                bitmap?.also { memory.put(key(url, size), it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Music: image failed: %s", e.javaClass.simpleName)
                synchronized(failed) { failed[url] = System.currentTimeMillis() }
                null
            }
        }
    }

    /** The bytes of a HA path (HaApi.bytes) or a hub URL (with the Odysseus bearer); null for anything else. */
    private suspend fun fetch(url: String): ByteArray? {
        if (url.startsWith("/api/")) return MusicRepository.imageBytes?.invoke(url)
        val base = OdysseusLink.baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || !url.startsWith("$base/")) return null
        val token = OdysseusLink.bearerToken() ?: return null
        return withContext(Dispatchers.IO) { httpGet(url, token) }
    }

    private fun httpGet(url: String, token: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            // Never follow a redirect: it would carry the token elsewhere.
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "image/*")
            if (conn.responseCode != 200) return null
            if (conn.contentType?.startsWith("image/") == false) return null
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Decodes [bytes] subsampled so the longest side is at least [px] (no larger than twice that). */
    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** [url]'s thumbnail at [size] while composed: from memory at once, else loaded; null for none or a failure. */
@Composable
fun rememberMusicImage(url: String?, size: Dp): Bitmap? {
    if (url == null) return null
    MusicImages.init(LocalContext.current)
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(MusicImages.cached(url, px), url, px) {
        // produceState keeps its value when the keys change (a recycled row, another detail):
        // start again from this url's bitmap, never the previous one.
        value = MusicImages.cached(url, px)
        if (value == null) value = MusicImages.load(url, px)
    }
    return bitmap
}

/** An item's thumbnail, or its kind's icon on the secondary container while there's none. */
@Composable
fun MediaThumb(item: MediaItem, size: Dp, shape: Shape = RoundedCornerShape(6.dp), modifier: Modifier = Modifier) {
    val bitmap = rememberMusicImage(item.imageUrl, size)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.size(size).clip(shape).background(colors.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Icon(LibrarySections.icon(item.kind), contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(size * 0.5f))
        }
    }
}
