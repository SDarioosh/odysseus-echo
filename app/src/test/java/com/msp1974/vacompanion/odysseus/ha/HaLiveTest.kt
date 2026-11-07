package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: the client against a real Home Assistant, for the owner's verification only.
// Skipped unless HA_URL and HA_TOKEN (a long-lived access token) are set, e.g.
//   HA_URL=http://192.168.1.2:8123 HA_TOKEN=… ./gradlew :app:testDebugUnitTest --tests '*HaLiveTest*' -i
// Read-only: one template, the state list, and a websocket subscribe; no service is called.

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HaLiveTest {
    @Test
    fun restAndWebsocketAgainstARealHomeAssistant() = runBlocking {
        val url = System.getenv("HA_URL").orEmpty().trimEnd('/')
        val token = System.getenv("HA_TOKEN").orEmpty()
        assumeTrue("HA_URL and HA_TOKEN not set", url.isNotBlank() && token.isNotBlank())
        val auth = object : HaAuth {
            override fun baseUrl() = url
            override suspend fun token(forceRefresh: Boolean) = token
        }
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val rest = HaRestApi(auth)
            println("HA template: " + rest.template("{{ states.media_player | count }} media players"))
            val players = rest.states { it.entityId.startsWith("media_player.") }
            println("HA states: ${players.size} media players")
            val ids = players.map { it.entityId }.toSet().ifEmpty { setOf("sun.sun") }
            val socket = HaSocket(auth, KtorHaSocketTransport(), scope, log = { logs += it; println(it) })
            val live = withTimeout(20_000) {
                socket.watch(ids).filterIsInstance<HaSocketUpdate.States>().first { it.states.isNotEmpty() }.states
            }
            println("HA ws: ${live.size} live states, e.g. ${live.values.first().entityId}=${live.values.first().state}")
            assertTrue(logs.contains("HA ws: auth_ok"))
        } finally {
            scope.cancel()
        }
    }
}
