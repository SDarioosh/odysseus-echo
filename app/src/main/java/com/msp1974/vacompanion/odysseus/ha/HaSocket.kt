package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: Home Assistant's websocket API (ws(s)://<ha>/api/websocket), for live states
// and service calls while something watches (the Music page). One connection, shared by every
// watch(); opened when the first collector arrives, closed when the last one leaves.
//   server: auth_required → client: {"type":"auth","access_token":…} → server: auth_ok
//   {"id":N,"type":"subscribe_entities","entity_ids":[…]}   → result, then events (HaEntities)
//   {"id":N,"type":"call_service",…,"return_response":true} → result {"response":…}
//   {"id":N,"type":"ping"} every 30 s                       → pong
// A dropped connection is reopened after 1, 2, 4 … 30 s (HaBackoff). auth_invalid gets one
// immediate retry with a refreshed token; a second one stops reconnecting until the next
// watch() starts (repeated invalid logins count toward Home Assistant's IP ban), and so does a
// refused sign-in (HaAuth.token throwing HA_AUTH_FAILED). A CancellationException from the transport
// while the loop is still active is a closed socket, not the loop's end (guarded()).
// The transport is an interface, so all of this is unit-tested with a fake (HaSocketTest); the
// real one is KtorHaSocketTransport.

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** One open websocket. */
interface HaSocketConnection {
    /** The next text message; null once the socket is closed. */
    suspend fun receive(): String?
    suspend fun send(text: String)
    suspend fun close()
}

fun interface HaSocketTransport {
    /** Opens [url]; throws when it can't. */
    suspend fun connect(url: String): HaSocketConnection
}

/** What watch() reports: the current states, or that the socket is down (poll REST instead). */
sealed interface HaSocketUpdate {
    data class States(val states: Map<String, HaState>) : HaSocketUpdate
    data object Down : HaSocketUpdate
}

/** The socket isn't open; nothing was sent (the caller may use REST instead). */
class HaSocketClosed : IOException("Home Assistant websocket not open")

class HaSocket(
    private val auth: HaAuth,
    private val transport: HaSocketTransport,
    private val scope: CoroutineScope,
    private val pingIntervalMs: Long = 30_000L,
    private val replyTimeoutMs: Long = 30_000L,
    private val newBackoff: () -> HaBackoff = { HaBackoff() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    private class Watcher(val entityIds: Set<String>, val out: SendChannel<HaSocketUpdate>) {
        @Volatile var session: Any? = null
        @Volatile var subscriptionId: Int = 0
        @Volatile var states: Map<String, HaState> = emptyMap()
    }

    /** One authenticated connection and what is waiting on it. */
    private class Session(val conn: HaSocketConnection) {
        val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        val subscriptions = ConcurrentHashMap<Int, Watcher>()
    }

    private val lock = Any()
    private val watchers = LinkedHashSet<Watcher>() // guarded by lock
    private var loop: Job? = null // guarded by lock
    @Volatile private var session: Session? = null

    // Home Assistant wants message ids to go up on a connection: an id is taken and sent under
    // this lock, so two senders never send them out of order.
    private val sendLock = Mutex()
    private var lastId = 0

    /** True while a connection is authenticated. */
    val isOpen: Boolean get() = session != null

    /** Live states of [entityIds] while collected; Down whenever the socket can't deliver them. */
    fun watch(entityIds: Set<String>): Flow<HaSocketUpdate> = callbackFlow {
        val w = Watcher(entityIds, channel)
        synchronized(lock) {
            watchers += w
            if (loop?.isActive != true) loop = scope.launch { runLoop() }
        }
        session?.let { s -> launch { runCatching { subscribe(s, w) } } }
        awaitClose {
            val stop: Job?
            synchronized(lock) {
                watchers -= w
                stop = if (watchers.isEmpty()) loop.also { loop = null } else null
            }
            val s = session
            if (stop != null) {
                stop.cancel()
            } else if (s != null && w.session === s) {
                // A subscribe still queued sees the watcher gone and sends nothing (subscribe()).
                unsubscribe(s, w, w.subscriptionId)
            }
        }
    }.conflate()

    /** A service call over the open socket. Throws HaSocketClosed when it isn't open. */
    suspend fun call(domain: String, service: String, data: JsonObject, returnResponse: Boolean): JsonElement? {
        val s = session ?: throw HaSocketClosed()
        val reply = request(s) { id ->
            message(id, "call_service") {
                put("domain", domain)
                put("service", service)
                put("service_data", data)
                if (returnResponse) put("return_response", true)
            }
        }
        if ((reply["success"] as? JsonPrimitive)?.booleanOrNull == true) {
            return if (returnResponse) (reply["result"] as? JsonObject)?.get("response") ?: JsonNull else null
        }
        val error = reply["error"] as? JsonObject
        val text = (error?.get("message") as? JsonPrimitive)?.content
            ?: (error?.get("code") as? JsonPrimitive)?.content ?: "failed"
        // HA refused the call itself, as REST's HTTP 400: callers tell it from a lost connection (0).
        throw HaException("Home Assistant: $domain.$service: $text", 400)
    }

    // --- The connection loop -------------------------------------------------------------

    private suspend fun runLoop() {
        val backoff = newBackoff()
        var refreshToken = false
        while (scope.isActive) {
            var s: Session? = null
            try {
                val token = try {
                    auth.token(refreshToken)
                } catch (e: HaException) {
                    if (e.code != HA_AUTH_FAILED) throw e
                    // Sign-in refused (e.g. invalid_grant): retrying only adds failed logins.
                    log("HA ws: ${e.message}; not reconnecting")
                    notifyDown()
                    return
                }
                val conn = guarded { transport.connect(wsUrl(auth.baseUrl())) }
                try {
                    withTimeoutOrNull(replyTimeoutMs) { authenticate(conn, token) }
                        ?: throw IOException("no answer to auth in time")
                } catch (e: Throwable) {
                    withContext(NonCancellable) { runCatching { conn.close() } }
                    throw e
                }
                refreshToken = false
                backoff.reset()
                log("HA ws: auth_ok")
                s = Session(conn)
                session = s
                synchronized(lock) { watchers.toList() }.forEach { subscribe(s, it) }
                coroutineScope {
                    val ping = launch { pingLoop(s) }
                    readLoop(s)
                    ping.cancel()
                }
                log("HA ws: closed by Home Assistant")
            } catch (e: CancellationException) {
                if (!currentCoroutineContext().isActive) throw e
                log("HA ws: closed (${e.message})") // the transport's, not this loop's
            } catch (e: HaException) {
                if (e.code == 401 && !refreshToken) {
                    log("HA ws: auth_invalid, retrying once with a refreshed token")
                    refreshToken = true
                    continue
                }
                log("HA ws: ${e.message}")
                if (e.code == 401) {
                    notifyDown()
                    log("HA ws: token refused twice; not reconnecting")
                    return
                }
            } catch (e: Exception) {
                log("HA ws: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (s != null) endSession(s)
            }
            notifyDown()
            sleep(backoff.nextDelayMs())
        }
    }

    private suspend fun authenticate(conn: HaSocketConnection, token: String) {
        val first = guarded { conn.receive() }?.let(::parse) ?: throw IOException("closed before auth")
        if (first.type() != "auth_required") throw IOException("unexpected ${first.type()} before auth")
        guarded { conn.send(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString()) }
        val reply = guarded { conn.receive() }?.let(::parse) ?: throw IOException("closed during auth")
        when (reply.type()) {
            "auth_ok" -> Unit
            "auth_invalid" -> throw HaException("auth_invalid: ${(reply["message"] as? JsonPrimitive)?.content}", 401)
            else -> throw IOException("unexpected ${reply.type()} during auth")
        }
    }

    /** Closes [s]: no longer the current session, its waiting requests fail. */
    private suspend fun endSession(s: Session) {
        synchronized(lock) { if (session === s) session = null }
        val closed = IOException("Home Assistant websocket closed")
        s.pending.values.forEach { it.completeExceptionally(closed) }
        s.pending.clear()
        withContext(NonCancellable) { runCatching { s.conn.close() } }
    }

    /** Tells every watcher the socket is down; they subscribe again on the next connection. */
    private fun notifyDown() {
        synchronized(lock) { watchers.toList() }.forEach { w ->
            w.session = null
            w.states = emptyMap()
            w.out.trySend(HaSocketUpdate.Down)
        }
    }

    private suspend fun readLoop(s: Session) {
        while (true) {
            val text = guarded { s.conn.receive() } ?: return
            when (val el = runCatching { json.parseToJsonElement(text) }.getOrNull()) {
                is JsonObject -> dispatch(s, el)
                is JsonArray -> el.forEach { (it as? JsonObject)?.let { o -> dispatch(s, o) } }
                else -> Unit
            }
        }
    }

    private fun dispatch(s: Session, o: JsonObject) {
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: return
        when (o.type()) {
            "event" -> {
                val w = s.subscriptions[id] ?: return
                val event = o["event"] as? JsonObject ?: return
                w.states = HaEntities.apply(w.states, event)
                w.out.trySend(HaSocketUpdate.States(w.states))
            }
            "result", "pong" -> {
                val waiting = s.pending.remove(id)
                if (waiting != null) {
                    waiting.complete(o)
                } else if (s.subscriptions.containsKey(id) && (o["success"] as? JsonPrimitive)?.booleanOrNull == false) {
                    log("HA ws: subscribe_entities refused: ${o["error"]}")
                }
            }
        }
    }

    private suspend fun pingLoop(s: Session) {
        while (true) {
            delay(pingIntervalMs)
            try {
                request(s) { id -> message(id, "ping") {} }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("HA ws: no pong, reconnecting")
                runCatching { s.conn.close() } // ends readLoop
                return
            }
        }
    }

    private suspend fun subscribe(s: Session, w: Watcher) {
        synchronized(lock) {
            if (w.session === s || w !in watchers) return
            w.session = s
        }
        var sub = 0
        sendNewOrSkip(s) { id ->
            // The watcher may have left while this waited for the send lock: nothing to send.
            if (!isWatching(w)) return@sendNewOrSkip null
            sub = id
            w.subscriptionId = id
            w.states = emptyMap()
            s.subscriptions[id] = w
            message(id, "subscribe_entities") { putJsonArray("entity_ids") { w.entityIds.sorted().forEach { add(JsonPrimitive(it)) } } }
        }
        // It left while the subscribe went out: its awaitClose may have missed the id.
        if (sub != 0 && !isWatching(w)) unsubscribe(s, w, sub)
    }

    private fun isWatching(w: Watcher) = synchronized(lock) { w in watchers }

    /** Ends [w]'s subscription [sub] on [s], once (whoever removes it from the map sends it). */
    private fun unsubscribe(s: Session, w: Watcher, sub: Int) {
        if (sub == 0 || s.subscriptions.remove(sub, w).not()) return
        scope.launch {
            runCatching { sendNew(s) { id -> message(id, "unsubscribe_events") { put("subscription", sub) } } }
        }
    }

    /** Sends a message with a new id and waits for its reply. */
    private suspend fun request(s: Session, build: (Int) -> JsonObject): JsonObject {
        val reply = CompletableDeferred<JsonObject>()
        var sent = 0
        try {
            try {
                sendNew(s) { id ->
                    sent = id
                    s.pending[id] = reply
                    build(id)
                }
            } catch (e: IOException) {
                throw HaSocketClosed() // the send failed: nothing went out
            }
            if (session !== s) throw IOException("Home Assistant websocket closed")
            return withTimeoutOrNull(replyTimeoutMs) { reply.await() }
                ?: throw IOException("no answer from Home Assistant in time")
        } finally {
            s.pending.remove(sent)
        }
    }

    private suspend fun sendNew(s: Session, build: (Int) -> JsonObject) = sendNewOrSkip(s, build)

    /** Takes a new id and sends what [build] makes of it; nothing (and no id) when it makes null. */
    private suspend fun sendNewOrSkip(s: Session, build: (Int) -> JsonObject?) {
        sendLock.withLock {
            val msg = build(lastId + 1) ?: return
            lastId++
            guarded { s.conn.send(msg.toString()) }
        }
    }

    /**
     * Runs a transport call. A CancellationException or ClosedSendChannelException from the
     * transport (Ktor's send on a closed session) while this coroutine is still active is a closed
     * socket (IOException), not this coroutine's cancellation: that would end the reconnect loop for
     * good and reach callers as a cancellation.
     */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: ClosedSendChannelException) {
        throw IOException("Home Assistant websocket closed", e)
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        throw IOException("Home Assistant websocket closed", e)
    }

    private fun message(id: Int, type: String, body: JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("id", id)
            put("type", type)
            body()
        }

    private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    private fun JsonObject.type() = (this["type"] as? JsonPrimitive)?.content

    companion object {
        /** "http://ha:8123" → "ws://ha:8123/api/websocket", https → wss. */
        fun wsUrl(baseUrl: String): String {
            val base = baseUrl.trimEnd('/')
            val ws = when {
                base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
                base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
                else -> throw HaException("Not a Home Assistant URL: $baseUrl")
            }
            return "$ws/api/websocket"
        }
    }
}
