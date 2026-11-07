package com.msp1974.vacompanion.odysseus.home.music

// Odysseus fork: live data for the Music page's tabs (docs/odysseus/music.md): a room's player
// state while composed, and its artwork through Home Assistant with a small cache.

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.msp1974.vacompanion.odysseus.music.MusicRepository
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Artwork is decoded no larger than this (px, longest side): the biggest player art is ~170 dp. */
private const val ART_MAX_PX = 512

/**
 * The live player state of [roomId] (MusicBackend.player) while composed; null before the first
 * state, without a room or a backend. Its positionAtMs is on the wall clock
 * (MusicActions.clockFor). For this device's own room ([MusicRepository.selfRoom]) Sendspin's
 * pushed artwork ([sendspin], HomePageContext.nowPlaying) fills in `artwork` while Sendspin plays.
 * A failing flow keeps the last state.
 */
@Composable
fun rememberPlayerState(roomId: String?, sendspin: SendspinNowPlaying? = null): PlayerState? {
    val backend = MusicRepository.backend
    val state by produceState<PlayerState?>(null, roomId, backend) {
        value = null
        if (roomId == null || backend == null) return@produceState
        backend.player(roomId)
            .catch { e -> Timber.d("Music: player %s: %s", roomId, e.message) }
            .collect { value = it }
    }
    val own = roomId != null && roomId == MusicRepository.selfRoom?.entityId
    return mergeSendspinArtwork(state, if (own) sendspin else null)
}

/** [state] with Sendspin's pushed artwork when it has none and Sendspin is playing a track. Pure. */
fun mergeSendspinArtwork(state: PlayerState?, sendspin: SendspinNowPlaying?): PlayerState? {
    if (state == null || sendspin == null || state.artwork != null) return state
    val art = sendspin.artwork ?: return state
    return if (sendspin.active) state.copy(artwork = art) else state
}

/**
 * The artwork for [state]: its `artUrl` (a Home Assistant path) loaded through HaApi.bytes and
 * cached ([ArtCache], 8 images), else its pushed `artwork`; null for neither (show a placeholder).
 */
@Composable
fun rememberRoomArt(state: PlayerState?): Bitmap? {
    val url = state?.artUrl
    val loader = MusicRepository.imageBytes
    val loaded by produceState(url?.let { MusicArt.cache.get(it) }, url, loader) {
        if (url == null || loader == null) {
            value = null
            return@produceState
        }
        MusicArt.cache.get(url)?.let { value = it; return@produceState }
        value = MusicArt.load(url, loader)
    }
    return loaded ?: state?.artwork
}

/** A tiny least-recently-used map. Pure, thread-safe (ArtCacheTest). */
class ArtCache<V : Any>(private val max: Int = 8) {
    private val map = object : LinkedHashMap<String, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > max
    }

    @Synchronized fun get(key: String): V? = map[key]
    @Synchronized fun put(key: String, value: V) { map[key] = value }
    @Synchronized fun keys(): List<String> = map.keys.toList()
}

/** The Music page's artwork cache and loader. */
object MusicArt {
    val cache = ArtCache<Bitmap>(8)

    /** Loads and decodes [path] with [loader]; null when it can't (logged). Caches what it loaded. */
    suspend fun load(path: String, loader: suspend (String) -> ByteArray): Bitmap? = try {
        val bytes = loader(path)
        withContext(Dispatchers.Default) { decode(bytes) }?.also { cache.put(path, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d("Music: art %s: %s", path, e.message)
        null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
