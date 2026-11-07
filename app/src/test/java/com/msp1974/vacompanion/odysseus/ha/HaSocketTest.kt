package com.msp1974.vacompanion.odysseus.ha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class HaSocketTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = FakeTransport()
    private val auth = FakeHaAuth("https://ha.example:8123")
    private val sleeps: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun socket(pingMs: Long = 60_000, replyMs: Long = 5_000) = HaSocket(
        auth, transport, scope, pingIntervalMs = pingMs, replyTimeoutMs = replyMs,
        sleep = { sleeps += it; delay(5) }, log = { logs += it },
    )

    @After
    fun tearDown() = scope.cancel()

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    /** Plays HA's side of the handshake; returns the token the client sent. */
    private suspend fun FakeConn.handshake(ok: Boolean = true): String {
        toClient.send("""{"type":"auth_required","ha_version":"2026.11.0"}""")
        val auth = obj(next())
        assertEquals("auth", auth.str("type"))
        toClient.send(if (ok) """{"type":"auth_ok","ha_version":"2026.11.0"}""" else """{"type":"auth_invalid","message":"Invalid access"}""")
        return auth.str("access_token")
    }

    private fun <T> run(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun wsUrlFromTheBaseUrl() {
        assertEquals("ws://192.168.1.2:8123/api/websocket", HaSocket.wsUrl("http://192.168.1.2:8123/"))
        assertEquals("wss://ha.example/api/websocket", HaSocket.wsUrl("https://ha.example"))
        try {
            HaSocket.wsUrl("ftp://x"); fail()
        } catch (e: HaException) {
        }
    }

    @Test
    fun watchAuthenticatesSubscribesAndDecodes() = run {
        val ws = socket()
        val updates = Channel<HaSocketUpdate>(Channel.UNLIMITED)
        val job = launch { ws.watch(setOf("media_player.kitchen", "media_player.attic")).collect { updates.send(it) } }
        val c = transport.nextConn()
        assertEquals("wss://ha.example:8123/api/websocket", c.url)
        assertEquals("T1", c.handshake())
        val sub = obj(c.next())
        assertEquals("subscribe_entities", sub.str("type"))
        assertEquals(listOf("media_player.attic", "media_player.kitchen"), sub["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        val id = sub["id"]!!.jsonPrimitive.int
        assertTrue(ws.isOpen)
        assertTrue(logs.contains("HA ws: auth_ok"))

        c.toClient.send("""{"id":$id,"type":"result","success":true,"result":null}""")
        c.toClient.send("""{"id":$id,"type":"event","event":{"a":{"media_player.kitchen":{"s":"playing","a":{},"c":"x","lc":1.0}}}}""")
        val first = withTimeout(5_000) { updates.receive() } as HaSocketUpdate.States
        assertEquals("playing", first.states.getValue("media_player.kitchen").state)
        c.toClient.send("""{"id":$id,"type":"event","event":{"c":{"media_player.kitchen":{"+":{"s":"paused","lc":2.0}}}}}""")
        var last: HaSocketUpdate
        do { last = withTimeout(5_000) { updates.receive() } } while ((last as HaSocketUpdate.States).states.getValue("media_player.kitchen").state != "paused")

        job.cancel()
        job.join()
        withTimeout(5_000) { while (!c.closed) delay(5) }
        delay(50)
        assertFalse(ws.isOpen)
    }

    @Test
    fun serviceCallsGoOverTheSocketWithReturnResponse() = run {
        val ws = socket()
        try {
            ws.call("media_player", "media_pause", JsonObject(emptyMap()), false); fail()
        } catch (e: HaSocketClosed) {
        }
        val job = launch { ws.watch(setOf("media_player.kitchen")).collect { } }
        val c = transport.nextConn()
        c.handshake()
        obj(c.next()) // subscribe
        val data = buildJsonObject { put("entity_id", "media_player.kitchen") }
        val answer = async { ws.call("music_assistant", "get_queue", data, returnResponse = true) }
        val msg = obj(c.next())
        assertEquals("call_service", msg.str("type"))
        assertEquals("music_assistant", msg.str("domain"))
        assertEquals("get_queue", msg.str("service"))
        assertEquals(data, msg["service_data"])
        assertEquals(JsonPrimitive(true), msg["return_response"])
        val id = msg["id"]!!.jsonPrimitive.int
        c.toClient.send("""{"id":$id,"type":"result","success":true,"result":{"context":{"id":"c"},"response":{"media_player.kitchen":{"next_item":null}}}}""")
        assertTrue((answer.await() as JsonObject).containsKey("media_player.kitchen"))

        val failing = async { runCatching { ws.call("media_player", "media_play", data, returnResponse = false) } }
        val m2 = obj(c.next())
        assertFalse(m2.containsKey("return_response"))
        assertTrue(m2["id"]!!.jsonPrimitive.int > id)
        c.toClient.send("""{"id":${m2["id"]},"type":"result","success":false,"error":{"code":"not_found","message":"Service not found."}}""")
        val e = failing.await().exceptionOrNull()
        assertTrue(e is HaException && e.message!!.contains("Service not found"))
        job.cancel()
    }

    @Test
    fun reconnectsWithBackoffAndSubscribesAgain() = run {
        val ws = socket()
        val updates = Channel<HaSocketUpdate>(Channel.UNLIMITED)
        val job = launch { ws.watch(setOf("media_player.kitchen")).collect { updates.send(it) } }
        val c1 = transport.nextConn()
        c1.handshake()
        obj(c1.next())
        c1.toClient.close() // HA restarts
        assertEquals(HaSocketUpdate.Down, withTimeout(5_000) { updates.receive() })
        val c2 = transport.nextConn()
        assertEquals(listOf(1_000L), sleeps.toList())
        c2.handshake()
        assertEquals("subscribe_entities", obj(c2.next()).str("type"))
        job.cancel()
    }

    @Test
    fun backoffGrowsWhileHaIsUnreachable() = run {
        transport.fail = true
        val ws = socket()
        val updates = Channel<HaSocketUpdate>(Channel.UNLIMITED)
        val job = launch { ws.watch(setOf("media_player.kitchen")).collect { updates.send(it) } }
        assertEquals(HaSocketUpdate.Down, withTimeout(5_000) { updates.receive() })
        withTimeout(5_000) { while (sleeps.size < 4) delay(5) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), sleeps.take(4))
        job.cancel()
    }

    @Test
    fun authInvalidRetriesOnceWithARefreshedTokenThenStops() = run {
        val ws = socket()
        val updates = Channel<HaSocketUpdate>(Channel.UNLIMITED)
        val job = launch { ws.watch(setOf("media_player.kitchen")).collect { updates.send(it) } }
        assertEquals("T1", transport.nextConn().handshake(ok = false))
        assertEquals("T2", transport.nextConn().handshake(ok = false))
        assertEquals(HaSocketUpdate.Down, withTimeout(5_000) { updates.receive() })
        delay(200)
        assertEquals(2, transport.connects)
        assertEquals(listOf(false, true), auth.refreshes)
        assertTrue(sleeps.isEmpty())
        job.cancel()
    }

    @Test
    fun aRefreshedTokenThatWorksKeepsGoing() = run {
        val ws = socket()
        val job = launch { ws.watch(setOf("media_player.kitchen")).collect { } }
        transport.nextConn().handshake(ok = false)
        val c = transport.nextConn()
        assertEquals("T2", c.handshake())
        assertEquals("subscribe_entities", obj(c.next()).str("type"))
        job.cancel()
    }

    @Test
    fun pingsAndReconnectsWithoutPong() = run {
        val ws = socket(pingMs = 20, replyMs = 200)
        val job = launch { ws.watch(setOf("media_player.kitchen")).collect { } }
        val c1 = transport.nextConn()
        c1.handshake()
        obj(c1.next()) // subscribe
        val ping = obj(c1.next())
        assertEquals("ping", ping.str("type"))
        c1.toClient.send("""{"id":${ping["id"]},"type":"pong"}""")
        assertEquals("ping", obj(c1.next()).str("type")) // the next one: no pong this time
        val c2 = transport.nextConn()
        assertTrue(c1.closed)
        c2.handshake()
        job.cancel()
    }

    @Test
    fun twoWatchersShareOneConnection() = run {
        val ws = socket()
        val a = launch { ws.watch(setOf("media_player.a")).collect { } }
        val c = transport.nextConn()
        c.handshake()
        assertEquals(listOf("media_player.a"), obj(c.next())["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        val b = launch { ws.watch(setOf("media_player.b")).collect { } }
        val subB = obj(c.next())
        assertEquals(listOf("media_player.b"), subB["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        b.cancel()
        val unsub = obj(c.next())
        assertEquals("unsubscribe_events", unsub.str("type"))
        assertEquals(subB["id"], unsub["subscription"])
        assertFalse(c.closed)
        a.cancel()
        withTimeout(5_000) { while (!c.closed) delay(5) }
        assertEquals(1, transport.connects)
    }
}
