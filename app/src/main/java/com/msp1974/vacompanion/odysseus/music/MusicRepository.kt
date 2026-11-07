package com.msp1974.vacompanion.odysseus.music

// Odysseus fork: the one process-wide holder of the Music page's state, as Compose state (the
// TodayRepository pattern). Holds the MusicBackend, the rooms, this device's own player and the
// selected room. The page calls attach() when it shows and detach() when it hides (MusicRefresh,
// composed by HomeHost); rooms are live only while attached. probe() answers "is there anything to
// show" while the page isn't (to offer it at all). Nothing here throws. See docs/odysseus/music.md.

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.odysseus.ha.HaApi
import com.msp1974.vacompanion.sendspin.SendspinSecurity
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

object MusicRepository {
    /** probe() asks Home Assistant again after this long. */
    const val PROBE_MS = 10 * 60_000L

    private const val PROBE_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val probeLock = Mutex()

    /** The backend; null until [install]. */
    @Volatile var backend: MusicBackend? = null
        private set

    /** Loads a Home Assistant path's bytes (player artwork); null until [installHa]. */
    @Volatile var imageBytes: (suspend (String) -> ByteArray)? = null
        private set

    private var signedIn: () -> Boolean = { false }
    private var area: () -> String? = { null }
    private var job: Job? = null
    private var lastProbeMs = 0L

    /** The MA rooms, this device first. Live while attached; the last list otherwise. */
    var rooms by mutableStateOf<List<Room>>(emptyList())
        private set

    /** This device's own player (contract I3), or null: no "Play here". */
    var selfRoom by mutableStateOf<SelfMatch?>(null)
        private set

    /** The room the page shows; see [select]. Remembered across restarts as MusicSettings.lastRoom. */
    var selectedRoomId by mutableStateOf<String?>(null)
        private set

    /** Home Assistant is signed in and MA has at least one room: the page may be offered. */
    var available by mutableStateOf(false)
        private set

    /** False while Home Assistant can't be reached: the page greys the last state. */
    var reachable by mutableStateOf(true)
        private set

    /** True between attach() and detach(). */
    var attached by mutableStateOf(false)
        private set

    /**
     * Sets the backend (replacing an earlier one). [signedIn] says whether Home Assistant is signed
     * in; [area] is this device's HA area (the room picker's second choice).
     */
    @Synchronized
    fun install(context: Context, backend: MusicBackend, signedIn: () -> Boolean, area: () -> String?) {
        MusicSettings.init(context)
        this.backend = backend
        this.signedIn = signedIn
        this.area = area
        lastProbeMs = 0L
        if (attached) {
            job?.cancel()
            job = start(backend)
        }
    }

    /**
     * [install] with an HaMusicBackend over [ha]: this device's player is found by its Sendspin
     * client id (remembered while Sendspin is off), its uuid and area from [config], and the
     * on-device override (MusicSettings.selfOverride).
     */
    fun installHa(context: Context, ha: HaApi, config: APPConfig) {
        MusicSettings.init(context)
        val backend = HaMusicBackend(
            ha = ha,
            selfClientId = {
                val id = SendspinSecurity.state.value.clientId
                if (id.isNotBlank()) MusicSettings.lastClientId = id
                id.ifBlank { MusicSettings.lastClientId }.ifBlank { null }
            },
            area = { config.area.ifBlank { null } },
            uuid = { config.uuid },
            selfOverride = { MusicSettings.selfOverride.ifBlank { null } },
        )
        imageBytes = { path -> ha.bytes(path) }
        install(context, backend, signedIn = { config.refreshToken.isNotBlank() }, area = { config.area.ifBlank { null } })
    }

    /** Starts live rooms (the page is shown). Safe to call more than once. */
    @Synchronized
    fun attach() {
        if (attached) return
        attached = true
        val b = backend ?: return
        job = start(b)
    }

    /** Stops live rooms (the page is hidden or the screen blanked). */
    @Synchronized
    fun detach() {
        if (!attached) return
        attached = false
        job?.cancel()
        job = null
    }

    private fun start(b: MusicBackend): Job = scope.launch {
        if (b is HaMusicBackend) {
            launch { b.self.collect { selfRoom = it; reselect() } }
            launch { b.reachable.collect { reachable = it } }
        }
        b.rooms.collect { update(it) }
    }

    private fun update(list: List<Room>) {
        rooms = list
        available = signedIn() && list.isNotEmpty()
        reselect()
    }

    private fun reselect() {
        val chosen = RoomChoice.keepOrDefault(selectedRoomId, rooms, selfRoom, area(), MusicSettings.lastRoom)
        if (chosen != selectedRoomId) selectedRoomId = chosen
    }

    /** The page's room picker chose [roomId]. */
    fun select(roomId: String) {
        selectedRoomId = roomId
        MusicSettings.lastRoom = roomId
    }

    /** The on-device "This device's player" changed: find this device's player again. */
    fun setSelfOverride(entityId: String?) {
        MusicSettings.selfOverride = entityId ?: ""
        val b = backend as? HaMusicBackend ?: return
        scope.launch { runCatching { reloadSelf(b) } }
    }

    /** Runs the rooms once and takes this device's player and the rooms from it (also while detached). */
    internal suspend fun reloadSelf(b: HaMusicBackend): List<Room> {
        val list = b.loadRooms()
        selfRoom = b.self.value
        reachable = true
        update(list)
        return list
    }

    /**
     * Whether the page may be offered, while it isn't shown: asks Home Assistant for the rooms
     * unless it did less than [minAgeMs] ago. Never throws.
     */
    suspend fun probe(minAgeMs: Long = PROBE_MS) = probeLock.withLock {
        val b = backend ?: return@withLock
        if (!signedIn()) {
            available = false
            return@withLock
        }
        if (attached) return@withLock
        val now = System.currentTimeMillis()
        if (lastProbeMs != 0L && now - lastProbeMs < minAgeMs) return@withLock
        lastProbeMs = now
        try {
            if (b is HaMusicBackend) {
                reloadSelf(b)
            } else {
                update(withTimeoutOrNull(PROBE_TIMEOUT_MS) { b.rooms.first { it.isNotEmpty() } } ?: emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Music: probe failed: %s", e.message)
            reachable = false
        }
    }
}
