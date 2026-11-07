package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: the app's one Home Assistant client for new features (docs/odysseus/
// home-assistant-client.md). Service calls go over the websocket while it is open (someone is
// watching), else REST; watch() keeps the websocket open while collected and polls REST every
// 5 s while the socket can't deliver. Templates, state lists and bytes are always REST.
// Get the process-wide instance with HaClient.get(deviceManager). Nothing opens the socket yet
// except a watch() (the Music page, later).

import com.msp1974.vacompanion.device.DeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            } catch (e: IOException) {
                // sent, but the answer was lost: don't run the service twice
                throw HaException("Home Assistant: $domain.$service: ${e.message}")
            }
        }
        return rest.call(domain, service, data, returnResponse)
    }

    override suspend fun states(filter: (HaState) -> Boolean): List<HaState> = rest.states(filter)

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
                        val states = rest.states { it.entityId in entityIds }.associateBy { it.entityId }
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

    override suspend fun token(forceRefresh: Boolean): String {
        val config = deviceManager.config
        if (config.refreshToken.isBlank()) throw HaException("not signed in to Home Assistant")
        try {
            deviceManager.authenticationManager.ensureValidSession(forceRefresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HaException("Home Assistant sign-in: ${e.message}")
        }
        return config.accessToken.ifBlank { throw HaException("not signed in to Home Assistant") }
    }
}
