package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: the real websocket under HaSocket: Ktor's WebSockets client plugin (in
// ktor-client-core) on the OkHttp engine the app already uses, so no new library. Certificates
// are checked by the platform as usual, like UrlConnectionHaHttp.

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class KtorHaSocketTransport(
    private val client: HttpClient = sharedClient,
    private val connectTimeoutMs: Long = 15_000L,
) : HaSocketTransport {

    override suspend fun connect(url: String): HaSocketConnection {
        val session = withTimeoutOrNull(connectTimeoutMs) { client.webSocketSession(url) }
            ?: throw IOException("no websocket connection in time")
        return object : HaSocketConnection {
            override suspend fun receive(): String? {
                while (true) {
                    val frame = session.incoming.receiveCatching().getOrNull() ?: return null
                    if (frame is Frame.Text) return frame.readText()
                }
            }

            override suspend fun send(text: String) = session.send(Frame.Text(text))

            override suspend fun close() {
                runCatching { session.close() }
            }
        }
    }

    companion object {
        val sharedClient: HttpClient by lazy { HttpClient(OkHttp) { install(WebSockets) } }
    }
}
