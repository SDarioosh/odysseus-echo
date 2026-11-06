package com.msp1974.vacompanion.odysseus

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** OdysseusLink's requests against a local server (fictional token). */
class OdysseusLinkHttpTest {
    private lateinit var server: HttpServer
    private val authSeen = ConcurrentHashMap<String, String>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat/voice/v1/moved") { ex ->
            ex.responseHeaders.add("Location", "$base/elsewhere")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/elsewhere") { ex ->
            authSeen["/elsewhere"] = ex.requestHeaders.getFirst("Authorization").orEmpty()
            val b = """{"ok": true}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun redirectsAreNotFollowed() {
        val reply = OdysseusLink.send("$base/api/chat/voice/v1/moved", "fictional-token")
        assertEquals(302, reply.code)
        assertEquals("", reply.body)
        // The token never went to the redirect's target.
        assertNull(authSeen["/elsewhere"])
    }
}
