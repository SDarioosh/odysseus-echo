package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: fakes for the Home Assistant client tests.

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/** Token "T1"; each forced refresh gives the next one ("T2", …). */
class FakeHaAuth(private val base: String = "http://ha.local:8123") : HaAuth {
    val refreshes = mutableListOf<Boolean>()
    private var n = 1
    override fun baseUrl() = base
    override suspend fun token(forceRefresh: Boolean): String {
        refreshes += forceRefresh
        if (forceRefresh) n++
        return "T$n"
    }
}

data class FakeCall(val method: String, val path: String, val bearer: String?, val body: String?)

/** Answers each request with [answer]; records them (path without the base URL). */
class FakeHaHttp(private val base: String = "http://ha.local:8123", val answer: (FakeCall) -> HaResponse) : HaHttp {
    val calls = mutableListOf<FakeCall>()
    override fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse {
        val c = FakeCall(method, url.removePrefix(base), bearer, body)
        calls += c
        return answer(c)
    }
}

/** One fake websocket; the test plays Home Assistant through [toClient] and [fromClient]. */
class FakeConn(val url: String) : HaSocketConnection {
    val toClient = Channel<String>(Channel.UNLIMITED)
    val fromClient = Channel<String>(Channel.UNLIMITED)
    @Volatile var closed = false
    override suspend fun receive(): String? = toClient.receiveCatching().getOrNull()
    override suspend fun send(text: String) {
        if (closed) throw java.io.IOException("closed")
        fromClient.send(text)
    }
    override suspend fun close() {
        closed = true
        toClient.close()
    }

    suspend fun next(): String = withTimeout(5_000) { fromClient.receive() }
}

/** Hands out a FakeConn per connect (to [opened]); [fail] makes connects throw. */
class FakeTransport : HaSocketTransport {
    val opened = Channel<FakeConn>(Channel.UNLIMITED)
    @Volatile var fail = false
    @Volatile var connects = 0
    override suspend fun connect(url: String): HaSocketConnection {
        connects++
        if (fail) throw java.io.IOException("refused")
        return FakeConn(url).also { opened.send(it) }
    }

    suspend fun nextConn(): FakeConn = withTimeout(5_000) { opened.receive() }
}
