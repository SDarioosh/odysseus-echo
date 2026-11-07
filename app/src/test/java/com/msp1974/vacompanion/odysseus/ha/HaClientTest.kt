package com.msp1974.vacompanion.odysseus.ha

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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class HaClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transport = FakeTransport()

    @After
    fun tearDown() = scope.cancel()

    private class FakeRest(@Volatile var states: List<HaState>) : HaApi {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun template(template: String) = ""
        override suspend fun call(domain: String, service: String, data: JsonObject, returnResponse: Boolean): JsonElement? {
            calls += "$domain.$service"
            return JsonPrimitive("rest")
        }
        override suspend fun states(filter: (HaState) -> Boolean): List<HaState> {
            calls += "states"
            return states.filter(filter)
        }
        override fun watch(entityIds: Set<String>): Flow<Map<String, HaState>> = emptyFlow()
        override suspend fun bytes(path: String) = ByteArray(0)
    }

    private fun state(id: String, s: String) = HaState(id, s, JsonObject(emptyMap()), 0L)

    private fun client(rest: FakeRest) = HaClient(
        rest, HaSocket(FakeHaAuth(), transport, scope, sleep = { delay(5) }),
        pollMs = 10, sleep = { delay(10) },
    )

    @Test
    fun callsUseRestWhileTheSocketIsClosed() = runBlocking {
        val rest = FakeRest(emptyList())
        assertEquals(JsonPrimitive("rest"), client(rest).call("media_player", "media_pause", JsonObject(emptyMap())))
        assertEquals(listOf("media_player.media_pause"), rest.calls)
        assertEquals(0, transport.connects)
    }

    @Test
    fun watchPollsRestWhileTheSocketIsDownAndUsesItOnceUp() = runBlocking {
        withTimeout(10_000) {
            val rest = FakeRest(listOf(state("media_player.kitchen", "idle"), state("media_player.other", "on")))
            val c = client(rest)
            val got = Channel<Map<String, HaState>>(Channel.UNLIMITED)
            val job = launch { c.watch(setOf("media_player.kitchen")).collect { got.send(it) } }
            assertEquals(setOf("media_player.kitchen"), got.receive().keys) // REST, socket not up yet

            val conn = transport.nextConn()
            conn.toClient.send("""{"type":"auth_required"}""")
            conn.next()
            conn.toClient.send("""{"type":"auth_ok"}""")
            val subId = Regex("\"id\":(\\d+)").find(conn.next())!!.groupValues[1]
            conn.toClient.send("""{"id":$subId,"type":"event","event":{"a":{"media_player.kitchen":{"s":"playing","a":{},"lc":1.0}}}}""")
            var m: Map<String, HaState>
            do { m = got.receive() } while (m.getValue("media_player.kitchen").state != "playing")

            // calls now go over the socket
            val call = launch { c.call("media_player", "media_pause", JsonObject(emptyMap())) }
            val msg = conn.next()
            assertTrue(msg, msg.contains("call_service"))
            val id = Regex("\"id\":(\\d+)").find(msg)!!.groupValues[1]
            conn.toClient.send("""{"id":$id,"type":"result","success":true,"result":{"context":{}}}""")
            call.join()
            assertTrue("media_player.media_pause" !in rest.calls)

            // the socket drops: polling again
            transport.fail = true
            val before = rest.calls.count { it == "states" }
            conn.toClient.close()
            rest.states = listOf(state("media_player.kitchen", "paused"))
            do { m = got.receive() } while (m.getValue("media_player.kitchen").state != "paused")
            assertTrue(rest.calls.count { it == "states" } > before)
            job.cancel()
        }
    }

    @Test
    fun emptyWatchNeedsNoSocket() = runBlocking {
        val got = mutableListOf<Map<String, HaState>>()
        client(FakeRest(emptyList())).watch(emptySet()).collect { got += it }
        assertEquals(listOf(emptyMap<String, HaState>()), got)
        assertEquals(0, transport.connects)
    }
}
