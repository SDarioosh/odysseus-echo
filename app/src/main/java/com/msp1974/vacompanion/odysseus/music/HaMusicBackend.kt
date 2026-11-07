package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: the Music page's backend over Home Assistant's Music Assistant integration (plan
// contract I2, docs/odysseus/music.md "How it works: the backend"). Reaches HA only through HaApi
// (odysseus/ha/). The JSON in and out is in HaMusicJson (pure, tested); this class does the calls,
// keeps the rooms live and finds this device's own player (SelfPlayer.kt, contract I3).
//
// Every call that changes what plays (play, control, transfer, group, ungroup) throws MusicLocked
// while Lock mode is on.

import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.ha.HaApi
import com.msp1974.vacompanion.odysseus.ha.HaException
import com.msp1974.vacompanion.odysseus.ha.HaState
import com.msp1974.vacompanion.odysseus.lock.LockMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class HaMusicBackend(
    private val ha: HaApi,
    private val selfClientId: () -> String?,
    private val area: () -> String?,
    // Odysseus fork additions beyond contract I2's constructor (all defaulted):
    private val uuid: () -> String = { "" },
    private val selfOverride: () -> String? = { null },
    private val isLocked: () -> Boolean = { LockMode.isLocked },
    private val imageUrl: (String) -> String? = {
        HaMusicJson.hubImageUrl(OdysseusLink.baseUrl, OdysseusLink.isConfigured, it)
    },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val roomsRefreshMs: Long = ROOMS_REFRESH_MS,
) : MusicBackend {

    companion object {
        /** The rooms template runs again this often while [rooms] is collected. */
        const val ROOMS_REFRESH_MS = 60_000L

        /** A player that appears or goes away runs the template again, at most this often. */
        const val RELOAD_MIN_GAP_MS = 10_000L

        /**
         * After Home Assistant refused the template (a token that isn't admin), rooms come from
         * /api/states and the template is tried again only after this long: each refusal is a
         * failed login at HA (its IP-ban counter).
         */
        const val TEMPLATE_RETRY_MS = 6 * 60 * 60_000L

        /** What library, search and detail say while the template is refused (no MA config entry). */
        const val NEEDS_ADMIN = "Library needs an admin Home Assistant sign-in"
    }

    private sealed interface Ev {
        data object Reload : Ev
        class States(val ids: Set<String>, val map: Map<String, HaState>) : Ev
        class WatchFailed(val ids: Set<String>) : Ev
    }

    private val loadLock = Mutex()
    @Volatile private var rows: List<RoomRow> = emptyList()
    @Volatile private var states: Map<String, HaState> = emptyMap()
    @Volatile private var entryId: String? = null
    /** When HA last refused the template (401/403), or null. */
    @Volatile private var templateRefusedAtMs: Long? = null

    private fun templateRefused(): Boolean =
        templateRefusedAtMs?.let { clock() - it < TEMPLATE_RETRY_MS } == true

    private val _self = MutableStateFlow<SelfMatch?>(null)
    /** This device's own player (I3), as of the last rooms update. */
    val self: StateFlow<SelfMatch?> = _self.asStateFlow()

    private val _reachable = MutableStateFlow(true)
    /** False after the last rooms update failed (Home Assistant offline); the rooms keep their last value. */
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())

    /**
     * Live while collected (and 5 s after): the rooms template runs at once, then every
     * [roomsRefreshMs] and when a player appears or goes away; the players' states are watched in
     * between. Keeps its last value while nobody collects; [loadRooms] updates it too.
     */
    override val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    init {
        scope.launch {
            SharingStarted.WhileSubscribed(5_000).command(_rooms.subscriptionCount)
                .distinctUntilChanged()
                .collectLatest { if (it == SharingCommand.START) liveRooms() }
        }
    }

    private suspend fun liveRooms() = coroutineScope {
        val events = Channel<Ev>(Channel.UNLIMITED)
        launch {
            while (isActive) {
                events.send(Ev.Reload)
                delay(roomsRefreshMs)
            }
        }
        var watchJob: Job? = null
        var watched: Set<String> = emptySet()
        var lastReloadMs = Long.MIN_VALUE / 2
        for (ev in events) {
            when (ev) {
                Ev.Reload -> {
                    lastReloadMs = clock()
                    val loaded = try {
                        loadRows()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w("Music: rooms failed: %s", e.message)
                        _reachable.value = false
                        null
                    } ?: continue
                    val ids = loaded.map { it.entityId }.toSet()
                    if (ids != watched) {
                        watchJob?.cancel()
                        watched = ids
                        states = states.filterKeys { it in ids }
                        watchJob = if (ids.isEmpty()) null else launch {
                            ha.watch(ids)
                                .catch { e ->
                                    Timber.w("Music: watch failed: %s", e.message)
                                    events.send(Ev.WatchFailed(ids))
                                }
                                .collect { events.send(Ev.States(ids, it)) }
                        }
                    }
                    publish()
                }
                is Ev.States -> if (ev.ids == watched) {
                    states = ev.map
                    publish()
                    if (membershipChanged(watched, ev.map) && clock() - lastReloadMs >= RELOAD_MIN_GAP_MS) {
                        events.trySend(Ev.Reload)
                    }
                }
                is Ev.WatchFailed -> if (ev.ids == watched) {
                    // Watched again at the next reload.
                    watched = emptySet()
                    watchJob = null
                }
            }
        }
    }

    /** A player the template didn't list showed up, or one it listed is gone. */
    private fun membershipChanged(watched: Set<String>, map: Map<String, HaState>): Boolean =
        map.keys.any { it !in watched } || (map.isNotEmpty() && watched.any { it !in map })

    /** Runs the rooms template once (also while [rooms] isn't collected); throws when HA can't be reached. */
    suspend fun loadRooms(): List<Room> {
        loadRows()
        return publish()
    }

    private suspend fun loadRows(): List<RoomRow> = loadLock.withLock {
        val loaded = if (templateRefused()) {
            statesRows()
        } else {
            try {
                HaMusicJson.parseRooms(ha.template(HaMusicJson.ROOMS_TEMPLATE)).also { templateRefusedAtMs = null }
            } catch (e: HaException) {
                if (e.code != 401 && e.code != 403) throw e
                // Not an admin token: no template (asked again after TEMPLATE_RETRY_MS). The
                // players are still in /api/states.
                Timber.w("Music: template refused (%d), reading states", e.code)
                templateRefusedAtMs = clock()
                statesRows()
            }
        }
        loaded.firstNotNullOfOrNull { it.entry }?.let { entryId = it }
        rows = loaded
        _reachable.value = true
        loaded
    }

    private suspend fun statesRows() = HaMusicJson.roomRowsFromStates(ha.states { HaMusicJson.isMaPlayerState(it) })

    /** Recomputes this device's player and the rooms from the last rows and states. */
    private fun publish(): List<Room> {
        val r = rows
        val s = states
        val candidates = r.map { HaMusicJson.candidate(it, s[it.entityId]) }
        val match = matchSelf(selfClientId(), uuid(), area(), selfOverride(), candidates)
        if (match != _self.value) {
            Timber.i("Music: this device's player: %s (%s)", match?.entityId ?: "none", match?.how ?: "-")
            _self.value = match
        }
        return HaMusicJson.buildRooms(r, s, match).also { _rooms.value = it }
    }

    override fun player(entityId: String): Flow<PlayerState> =
        ha.watch(setOf(entityId))
            .mapNotNull { it[entityId] }
            .map { HaMusicJson.playerState(it, clock()) }
            .distinctUntilChanged()

    override suspend fun queue(entityId: String): QueueSummary? {
        val c = HaMusicJson.queueCall(entityId)
        val response = try {
            ha.call(c.domain, c.service, c.data, returnResponse = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (HaMusicJson.isNoQueue(e)) return null
            throw e
        }
        return HaMusicJson.parseQueue(response, entityId, imageUrl)
    }

    /** MA's config entry id, from the rooms template (run once if it hasn't run yet). */
    private suspend fun entry(): String {
        entryId?.let { return it }
        if (templateRefused()) throw HaException(NEEDS_ADMIN)
        loadRows()
        entryId?.let { return it }
        throw HaException(if (templateRefused()) NEEDS_ADMIN else "Music Assistant isn't set up in Home Assistant")
    }

    override suspend fun library(kind: MediaKind, order: LibraryOrder, favoritesOnly: Boolean,
                                 offset: Int, limit: Int): LibraryPage {
        val c = HaMusicJson.libraryCall(entry(), kind, order, favoritesOnly, offset, limit)
        val response = ha.call(c.domain, c.service, c.data, returnResponse = true)
        return HaMusicJson.parseLibrary(response, offset, limit, imageUrl)
    }

    override suspend fun search(text: String, kinds: Set<MediaKind>, limit: Int): Map<MediaKind, List<MediaItem>> {
        val q = text.trim()
        if (q.isEmpty() || kinds.isEmpty()) return emptyMap()
        val c = HaMusicJson.searchCall(entry(), q, kinds, limit)
        val response = ha.call(c.domain, c.service, c.data, returnResponse = true)
        return HaMusicJson.parseSearch(response, kinds, imageUrl)
    }

    /**
     * An artist's albums and tracks, or an album's tracks (HaMusicDetail): HA's MA actions have no
     * "tracks of" call, so this searches by name and keeps what belongs. Anything else: nothing.
     */
    override suspend fun detail(item: MediaItem): MediaDetail = when (item.kind) {
        MediaKind.ARTIST -> {
            val c = HaMusicDetail.artistCall(entry(), item)
            HaMusicDetail.parseArtist(ha.call(c.domain, c.service, c.data, returnResponse = true), item, imageUrl)
        }
        MediaKind.ALBUM -> {
            val entry = entry()
            val s = HaMusicDetail.albumSearchCall(entry, item)
            val found = ha.call(s.domain, s.service, s.data, returnResponse = true)
            val l = HaMusicDetail.albumLibraryCall(entry, item)
            // The library pass only adds tracks the search missed; its failure isn't the view's.
            val library = try {
                ha.call(l.domain, l.service, l.data, returnResponse = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d("Music: album library search failed: %s", e.message)
                null
            }
            HaMusicDetail.parseAlbum(listOfNotNull(found, library), item, imageUrl)
        }
        else -> MediaDetail()
    }

    private fun guard() {
        if (isLocked()) throw MusicLocked()
    }

    private suspend fun run(c: HaCall) {
        ha.call(c.domain, c.service, c.data)
    }

    override suspend fun play(item: MediaItem, roomId: String, enqueue: Enqueue, radio: Boolean) {
        guard()
        run(HaMusicJson.playCall(item, roomId, enqueue, radio))
    }

    override suspend fun control(roomId: String, control: Control, value: Any?) {
        guard()
        run(HaMusicJson.controlCall(roomId, control, value))
    }

    override suspend fun transfer(fromRoomId: String, toRoomId: String) {
        guard()
        if (fromRoomId == toRoomId) return
        run(HaMusicJson.transferCall(fromRoomId, toRoomId))
    }

    override suspend fun group(leaderId: String, memberIds: List<String>) {
        guard()
        if (memberIds.none { it != leaderId }) return
        run(HaMusicJson.joinCall(leaderId, memberIds))
    }

    override suspend fun ungroup(roomId: String) {
        guard()
        run(HaMusicJson.unjoinCall(roomId))
    }
}
