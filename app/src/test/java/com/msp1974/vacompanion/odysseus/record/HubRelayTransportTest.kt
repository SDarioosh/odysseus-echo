package com.msp1974.vacompanion.odysseus.record

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.util.Collections

class HubRelayTransportTest {
    private lateinit var server: HttpServer
    private val tokensSeen: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/codex/athena/chunk") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("Location", "$base/elsewhere")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/elsewhere") { ex ->
            tokensSeen += ex.requestHeaders.getFirst("Authorization")
            val ok = """{"ok": true}""".toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.createContext("/big") { ex ->
            ex.requestBody.readBytes()
            val big = ("""{"ok": true, "pad": """" + "x".repeat(100_000) + "\"}").toByteArray()
            ex.sendResponseHeaders(200, big.size.toLong())
            runCatching { ex.responseBody.use { it.write(big) } }
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun aRedirectIsNotFollowedAndWaitsForLater() {
        val r = HubRelayTransport.postTo("$base/api/codex/athena/chunk", "secret-token", "application/json", "{}".toByteArray())
        assertEquals(302, r.code)
        assertNull(r.body)
        assertTrue("the token went to the redirect target", tokensSeen.isEmpty())
        assertEquals(UploadClass.RETRY, UploadRules.classify(r.code, r.body))
    }

    @Test
    fun aLongBodyIsCut() {
        val r = HubRelayTransport.postTo("$base/big", "secret-token", "application/json", "{}".toByteArray())
        assertEquals(200, r.code)
        assertEquals(HubRelayTransport.MAX_BODY_BYTES, r.body!!.length)
        assertEquals(UploadClass.OK, UploadRules.classify(r.code, r.body))
    }

    @Test
    fun readCappedStopsAtTheCap() {
        assertEquals("abc", HubRelayTransport.readCapped(ByteArrayInputStream("abcdef".toByteArray()), 3))
        assertEquals("ab", HubRelayTransport.readCapped(ByteArrayInputStream("ab".toByteArray()), 3))
    }

    @Test
    fun noServerIsNoAnswer() {
        val port = server.address.port
        server.stop(0)
        val r = HubRelayTransport.postTo("http://127.0.0.1:$port/x", "t", "application/json", ByteArray(0))
        assertNull(r.code)
    }
}
