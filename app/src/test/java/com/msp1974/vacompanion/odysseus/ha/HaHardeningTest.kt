package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: regression tests for the Home Assistant client review (refused sign-in, non-admin
// 401s, body cap, per-entity polling, transport cancellations, subscription leak).

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException

class HaHardeningTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000) { block() } }

    /** A REST side whose every state read fails the way DeviceHaAuth fails a refused refresh. */
    private class RefreshRefusedRest : HaApi {
        val reads: MutableList<Set<String>> = Collections.synchronizedList(mutableListOf())
        override suspend fun template(template: String) = ""
        override suspend fun call(domain: String, service: String, data: JsonObject, returnResponse: Boolean): JsonElement? =
            JsonPrimitive("rest")
        override suspend fun states(filter: (HaState) -> Boolean): List<HaState> =
            throw signInFailure(400, "invalid_grant")
        override suspend fun statesOf(entityIds: Set<String>): Map<String, HaState> {
            reads += entityIds
            throw signInFailure(400, "invalid_grant")
        }
        override fun watch(entityIds: Set<String>): Flow<Map<String, HaState>> = emptyFlow()
        override suspend fun bytes(path: String) = ByteArray(0)
    }

    /** An HaAuth whose sign-in Home Assistant refuses. */
    private class RefusedAuth : HaAuth {
        @Volatile var tokens = 0
        override fun baseUrl() = "http://ha.local:8123"
        override suspend fun token(forceRefresh: Boolean): String {
            tokens++
            throw signInFailure(400, "invalid_grant")
        }
    }

    // --- 1. A refused refresh token backs off like a 401 -------------------------------------

    @Test
    fun aRefusedRefreshIsAnAuthFailureButNoAnswerIsNot() {
        assertEquals(HA_AUTH_FAILED, signInFailure(400, "invalid_grant").code)
        assertEquals(HA_AUTH_FAILED, signInFailure(403, "x").code)
        assertEquals(0, signInFailure(0, "timeout").code)
        assertEquals(0, signInFailure(502, "bad gateway").code)
    }

    @Test
    fun refusedRefreshTokenBacksOffPollingAndStopsTheSocket() = run {
        val waits: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val transport = FakeTransport()
        val auth = RefusedAuth()
        val rest = RefreshRefusedRest()
        val c = HaClient(
            rest, HaSocket(auth, transport, scope, sleep = { delay(5) }),
            pollMs = 5_000, authFailedPollMs = 300_000, sleep = { waits += it; delay(5) },
        )
        val job = launch { c.watch(setOf("media_player.kitchen")).collect { } }
        delay(300)
        job.cancel()
        assertTrue("waits were $waits", waits.isNotEmpty() && waits.all { it == 300_000L })
        // The socket asked for a token once, never connected and stopped (no reconnect loop).
        assertEquals(1, auth.tokens)
        assertEquals(0, transport.connects)
    }

    // --- 2. A 401 to a fresh token isn't retried; a refused refresh isn't tried twice --------

    private class FreshAuth(private val fresh: Boolean) : HaAuth {
        val refreshes = mutableListOf<Boolean>()
        override fun baseUrl() = "http://ha.local:8123"
        override suspend fun token(forceRefresh: Boolean): String {
            refreshes += forceRefresh
            return if (forceRefresh) "T2" else "T1"
        }
        override fun isFresh(token: String) = fresh
    }

    @Test
    fun a401ToAFreshTokenIsNotRetried() = runBlocking {
        val auth = FreshAuth(fresh = true)
        var tries = 0
        try {
            withAuthRetry<String>(auth) { tries++; throw HaException("401", 401) }
            fail()
        } catch (e: HaException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, tries)
        assertEquals(listOf(false), auth.refreshes)
    }

    @Test
    fun a401ToAnOlderTokenIsStillRetriedOnce() = runBlocking {
        val auth = FreshAuth(fresh = false)
        val tokens = mutableListOf<String>()
        withAuthRetry(auth) { t -> tokens += t; if (t == "T1") throw HaException("401", 401); "ok" }
        assertEquals(listOf("T1", "T2"), tokens)
    }

    @Test
    fun aRefusedRefreshIsNotTriedTwice() = runBlocking {
        val auth = RefusedAuth()
        try {
            withAuthRetry(auth) { "never" }
            fail()
        } catch (e: HaException) {
            assertEquals(HA_AUTH_FAILED, e.code)
        }
        assertEquals(1, auth.tokens)
    }

    @Test
    fun nonAdminTemplateIsOneRequestAtHomeAssistant() = runBlocking {
        val http = FakeHaHttp { HaResponse(401, """{"message":"Unauthorized"}""") }
        val api = HaRestApi(FreshAuth(fresh = true), http, { _, _, _ -> HaBytes(404, ByteArray(0)) }, Dispatchers.Unconfined)
        try {
            api.template("{{ 1 }}"); fail()
        } catch (e: HaException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, http.calls.size)
    }

    @Test
    fun tokenFreshness() {
        var now = 1_000_000L
        val f = TokenFreshness(freshMs = 60_000, clock = { now })
        f.seen("A", "A") // first seen at start-up: age unknown
        assertFalse(f.isFresh("A"))
        f.seen("A", "B") // refreshed just now
        assertTrue(f.isFresh("B"))
        assertFalse(f.isFresh("A"))
        now += 60_001
        assertFalse(f.isFresh("B"))
        val g = TokenFreshness(freshMs = 60_000, clock = { now })
        g.seen("", "C") // the first token, obtained by this process
        assertTrue(g.isFresh("C"))
    }

    // --- 3. Body cap and per-entity polling ---------------------------------------------------

    @Test
    fun aBodyOverTheCapIsAnErrorNotHalfAnAnswer() {
        assertEquals("x".repeat(10), UrlConnectionHaHttp.readText(StringReader("x".repeat(10)), 10, "http://ha/api/states"))
        try {
            UrlConnectionHaHttp.readText(StringReader("x".repeat(20_000)), 10_000, "http://ha:8123/api/states?x=secret")
            fail()
        } catch (e: HaException) {
            assertEquals("Home Assistant's answer to /api/states is larger than 9 KB", e.message)
        }
    }

    @Test
    fun statesOfGetsEachEntityAndSkipsUnknownOnes() = runBlocking {
        val http = FakeHaHttp { c ->
            when (c.path) {
                "/api/states/media_player.a" -> HaResponse(200, """{"entity_id":"media_player.a","state":"idle","attributes":{}}""")
                else -> HaResponse(404, """{"message":"Entity not found."}""")
            }
        }
        val api = HaRestApi(FakeHaAuth(), http, { _, _, _ -> HaBytes(404, ByteArray(0)) }, Dispatchers.Unconfined)
        val got = api.statesOf(setOf("media_player.b", "media_player.a", "bad id/../x"))
        assertEquals(setOf("media_player.a"), got.keys)
        assertEquals(listOf("/api/states/media_player.a", "/api/states/media_player.b"), http.calls.map { it.path })
    }

    @Test
    fun theClientPollsOnlyTheWatchedEntities() = run {
        val rest = RefreshRefusedRest()
        val c = HaClient(rest, HaSocket(FakeHaAuth(), FakeTransport().apply { fail = true }, scope, sleep = { delay(5) }),
            pollMs = 10, sleep = { delay(10) })
        val job = launch { c.watch(setOf("media_player.kitchen")).collect { } }
        withTimeout(5_000) { while (rest.reads.isEmpty()) delay(5) }
        job.cancel()
        assertEquals(setOf("media_player.kitchen"), rest.reads.first())
    }

    // --- 4. A transport's CancellationException is a closed socket ----------------------------

    /** A connection whose sends can throw CancellationException (Ktor's send on a closed session). */
    private class CancellingConn : HaSocketConnection {
        val toClient = Channel<String>(Channel.UNLIMITED)
        val fromClient = Channel<String>(Channel.UNLIMITED)
        @Volatile var cancelSends = false
        override suspend fun receive(): String? = toClient.receiveCatching().getOrNull()
        override suspend fun send(text: String) {
            if (cancelSends) throw CancellationException("ws session cancelled")
            fromClient.send(text)
        }
        override suspend fun close() { toClient.close() }
    }

    @Test
    fun aCancelledTransportSendReconnectsInsteadOfEndingTheLoop() = run {
        val conns = Channel<CancellingConn>(Channel.UNLIMITED)
        var connects = 0
        val transport = HaSocketTransport {
            connects++
            CancellingConn().also { it.cancelSends = connects == 1; conns.send(it) }
        }
        val ws = HaSocket(FakeHaAuth(), transport, scope, sleep = { delay(5) })
        val job = launch { ws.watch(setOf("media_player.kitchen")).collect { } }
        val first = conns.receive()
        first.toClient.send("""{"type":"auth_required"}""") // the auth send is cancelled
        val second = withTimeout(5_000) { conns.receive() }
        second.toClient.send("""{"type":"auth_required"}""")
        assertTrue(withTimeout(5_000) { second.fromClient.receive() }.contains("\"auth\""))
        job.cancel()
    }

    @Test
    fun aCancelledTransportSendDuringACallFallsBackToRest() = run {
        val conn = CancellingConn()
        val rest = RefreshRefusedRest()
        val c = HaClient(rest, HaSocket(FakeHaAuth(), { conn }, scope, sleep = { delay(5) }), pollMs = 10, sleep = { delay(10) })
        val job = launch { c.watch(setOf("media_player.kitchen")).collect { } }
        conn.toClient.send("""{"type":"auth_required"}""")
        withTimeout(5_000) { conn.fromClient.receive() } // auth
        conn.toClient.send("""{"type":"auth_ok"}""")
        withTimeout(5_000) { conn.fromClient.receive() } // subscribe
        conn.cancelSends = true
        // Nothing went out over the socket: REST answers, and no cancellation reaches the caller.
        assertEquals(JsonPrimitive("rest"), c.call("media_player", "media_pause", JsonObject(emptyMap())))
        job.cancel()
    }

    // --- 5. A watcher that leaves while its subscribe is queued leaves no subscription --------

    /** FakeConn-like, but a send can be held at a gate (holding HaSocket's send lock). */
    private class GateConn : HaSocketConnection {
        val toClient = Channel<String>(Channel.UNLIMITED)
        val fromClient = Channel<String>(Channel.UNLIMITED)
        @Volatile var gate: CompletableDeferred<Unit>? = null
        override suspend fun receive(): String? = toClient.receiveCatching().getOrNull()
        override suspend fun send(text: String) {
            gate?.await()
            fromClient.send(text)
        }
        override suspend fun close() { toClient.close() }
        suspend fun next(): String = withTimeout(5_000) { fromClient.receive() }
        suspend fun handshake() {
            toClient.send("""{"type":"auth_required"}""")
            next()
            toClient.send("""{"type":"auth_ok"}""")
        }
    }

    @Test
    fun aWatcherThatLeavesWhileItsSubscribeIsQueuedIsNotSubscribed() = run {
        val conns = Channel<GateConn>(Channel.UNLIMITED)
        val ws = HaSocket(FakeHaAuth(), { GateConn().also { conns.send(it) } }, scope, sleep = { delay(5) })
        val a = launch { ws.watch(setOf("media_player.a")).collect { } }
        val c1 = conns.receive()
        c1.handshake()
        assertTrue(c1.next().contains("media_player.a"))
        val b = launch { ws.watch(setOf("media_player.b")).collect { } }
        assertTrue(c1.next().contains("media_player.b"))
        val c = launch { ws.watch(setOf("media_player.c")).collect { } }
        assertTrue(c1.next().contains("media_player.c"))

        // C leaves; its unsubscribe is held at the gate, holding the send lock.
        val gate = CompletableDeferred<Unit>()
        c1.gate = gate
        c.cancel()
        delay(100)
        // HA drops the connection; on the next one A's subscribe waits for the send lock.
        c1.toClient.close()
        val c2 = conns.receive()
        c2.handshake()
        delay(100)
        a.cancel() // A leaves while its subscribe is queued
        delay(100)
        gate.complete(Unit)

        val sent = c2.next()
        assertTrue(sent, sent.contains("subscribe_entities") && sent.contains("media_player.b"))
        delay(200)
        assertTrue(c2.fromClient.tryReceive().getOrNull().let { it == null || !it.contains("media_player.a") })
        b.cancel()
    }
}
