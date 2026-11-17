package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: the app's one Home Assistant client for new features (docs/odysseus/
// home-assistant-client.md). Service calls go over the websocket while it is open (someone is
// watching), else REST; watch() keeps the websocket open while collected and polls REST every
// 5 s while the socket can't deliver (GET /api/states/<id> per watched id, not the whole list).
// Templates, state lists and bytes are always REST. Get the process-wide instance with
// HaClient.get(deviceManager); its users are the Music page (MusicRepository, the only watch()),
// music on other speakers (home/players/) and the clock's weather (home/weather/).

import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.authentication.AuthenticationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class HaClient(
    private val rest: HaApi,
    private val socket: HaSocket,
    private val pollMs: Long = 5_000L,
    private val authFailedPollMs: Long = 5 * 60_000L,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : HaApi {

    override suspend fun template(template: String): String = rest.template(template)

    override suspend fun call(domain: String, service: String, data: JsonObject, returnResponse: Boolean): JsonElement? {
        if (socket.isOpen) {
            try {
                return socket.call(domain, service, data, returnResponse)
            } catch (e: HaSocketClosed) {
                // closed before anything was sent: REST below
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                // the transport's, not ours: as for a lost answer
                throw HaException("Home Assistant: $domain.$service: websocket closed")
            } catch (e: IOException) {
                // sent, but the answer was lost: don't run the service twice
                throw HaException("Home Assistant: $domain.$service: ${e.message}")
            }
        }
        return rest.call(domain, service, data, returnResponse)
    }

    override suspend fun states(filter: (HaState) -> Boolean): List<HaState> = rest.states(filter)

    override suspend fun statesOf(entityIds: Set<String>): Map<String, HaState> = rest.statesOf(entityIds)

    override suspend fun bytes(path: String): ByteArray = rest.bytes(path)

    override fun watch(entityIds: Set<String>): Flow<Map<String, HaState>> {
        if (entityIds.isEmpty()) return flowOf(emptyMap())
        return channelFlow {
            val live = MutableStateFlow(false)
            launch {
                socket.watch(entityIds).collect { u ->
                    when (u) {
                        is HaSocketUpdate.States -> {
                            live.value = true
                            send(u.states)
                        }
                        HaSocketUpdate.Down -> live.value = false
                    }
                }
            }
            launch {
                while (true) {
                    live.first { !it }
                    val wait = try {
                        val states = rest.statesOf(entityIds)
                        if (!live.value) send(states)
                        pollMs
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HaException) {
                        Timber.w("HA: polling states failed: %s", e.message)
                        if (e.code == 401) authFailedPollMs else pollMs
                    } catch (e: Exception) {
                        Timber.w("HA: polling states failed: %s", e.message)
                        pollMs
                    }
                    sleep(wait)
                }
            }
        }.distinctUntilChanged()
    }

    companion object {
        @Volatile private var instance: HaClient? = null

        /** The process-wide client (one websocket for the whole app). */
        fun get(deviceManager: DeviceManager): HaClient =
            instance ?: synchronized(this) {
                instance ?: create(DeviceHaAuth(deviceManager)).also { instance = it }
            }

        fun create(auth: HaAuth): HaClient {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val socket = HaSocket(auth, KtorHaSocketTransport(), scope, log = { Timber.d(it) })
            return HaClient(HaRestApi(auth), socket)
        }
    }
}

/** The device's Home Assistant sign-in (the token from the phone setup or the dashboard login). */
class DeviceHaAuth(private val deviceManager: DeviceManager) : HaAuth {
    override fun baseUrl(): String =
        deviceManager.authenticationManager.getBaseUrl().toString().removeSuffix("/")

    private val freshness = TokenFreshness()

    override suspend fun token(forceRefresh: Boolean): String {
        val config = deviceManager.config
        if (config.refreshToken.isBlank()) throw HaException("not signed in to Home Assistant", HA_AUTH_FAILED)
        val before = config.accessToken
        try {
            deviceManager.authenticationManager.ensureValidSession(forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthenticationException) {
            throw signInFailure(e.httpCode, e.message)
        } catch (e: Exception) {
            throw HaException("Home Assistant sign-in: ${e.message}")
        }
        val token = config.accessToken.ifBlank { throw HaException("not signed in to Home Assistant", HA_AUTH_FAILED) }
        freshness.seen(before, token)
        return token
    }

    override fun isFresh(token: String): Boolean = freshness.isFresh(token)
}

/**
 * A failed token refresh as HaException: Home Assistant refused it (HTTP 4xx, e.g. 400
 * invalid_grant) → [HA_AUTH_FAILED], so callers back off as after a 401; no answer (code 0) or a
 * server error → a plain failure (retried as for an unreachable HA). Pure.
 */
fun signInFailure(httpCode: Int, message: String?): HaException =
    if (httpCode in 400..499) HaException("Home Assistant refused the sign-in: $message", HA_AUTH_FAILED)
    else HaException("Home Assistant sign-in: $message")

/**
 * Remembers when this process got a new access token, so [isFresh] says whether a token was
 * obtained less than [freshMs] ago. A token first seen at start-up is of unknown age: not fresh.
 */
class TokenFreshness(
    private val freshMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var token: String? = null
    @Volatile private var since = 0L

    /** [after] is the token now; [before] the one held before asking for it. */
    fun seen(before: String, after: String) {
        if (after == token) return
        // A change from a token we held is a refresh just now; the first one seen has no known age.
        since = if (before != after || token != null) clock() else 0L
        token = after
    }

    fun isFresh(t: String): Boolean = t == token && since != 0L && clock() - since < freshMs
}
