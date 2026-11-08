package com.msp1974.vacompanion.odysseus

// The device's HTTP to its own Odysseus hub, in one place. Every request goes
// through OdysseusLink.send (Bearer voice token, redirects not followed, bodies capped); features
// ask through the HubHttp seam (OdysseusLink on the device, a fake in tests). The streamed APK
// download (odysseus/update/, OdysseusLink.openGet) is the one exception.

import kotlinx.serialization.json.Json

/**
 * A hub answer: the HTTP code, the body ("" unless 2xx; with [HubCall.errorBody] also the start
 * of an error answer), and its Date header ([dateMs], the hub's clock), or null without one.
 */
data class HubReply(val code: Int, val body: String, val dateMs: Long? = null)

/** One request to `<voice base><path>`: a GET without [body]. */
class HubCall(
    val path: String,
    val contentType: String? = null,
    val body: ByteArray? = null,
    val readTimeoutMs: Int = 6000,
    /** Also read the start of an error answer (OdysseusLink.ERROR_BODY_MAX chars). */
    val errorBody: Boolean = false,
)

/** How features ask the hub. Throws when not linked or unreachable. */
fun interface HubHttp {
    suspend fun request(call: HubCall): HubReply
}

/**
 * The Json for hub answers: unknown keys are ignored and a null or bad value gets its default, so
 * additive hub changes stay readable; lenient for the parsers that always were (Today, Done).
 */
val HubJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

/** A hub answer's status for logs and screens; features add their own cases before it. */
fun hubStatusText(code: Int): String = when (code) {
    401, 403 -> "Token refused ($code)"
    else -> "HTTP $code"
}
