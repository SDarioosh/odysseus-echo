package com.msp1974.vacompanion.odysseus.ha

// Odysseus fork: where the Home Assistant client gets its URL and token, and the one rule for a
// refused token. Pure Kotlin (HaAuthRetryTest); the device's own HaAuth is DeviceHaAuth
// (HaClient.kt).

import kotlin.coroutines.cancellation.CancellationException

interface HaAuth {
    /** Home Assistant's base URL without a trailing slash, e.g. "http://192.168.1.2:8123". */
    fun baseUrl(): String

    /** A valid access token; [forceRefresh] gets a new one first. Throws HaException when
     *  the device isn't signed in to Home Assistant or the refresh fails. */
    suspend fun token(forceRefresh: Boolean = false): String

    /**
     * True when [token] was obtained moments ago (a refresh just now), so a 401 to it isn't about
     * the token (a non-admin user on an admin-only API) and a refresh wouldn't help. Default false.
     */
    fun isFresh(token: String): Boolean = false
}

/** HaException.code for a refused sign-in: a 401, or a refresh Home Assistant refused. */
const val HA_AUTH_FAILED = 401

/**
 * Runs [block] with a token; when it throws HaException(code = 401), refreshes the token once and
 * runs it again. A second 401 is thrown to the caller: at most one retry per call, because
 * repeated 401s count toward Home Assistant's IP ban. No retry when the token was fresh
 * ([HaAuth.isFresh]: the 401 is a permission, e.g. /api/template for a non-admin user), and none
 * when getting the token itself failed (a refused refresh is not tried twice).
 */
suspend fun <T> withAuthRetry(auth: HaAuth, block: suspend (token: String) -> T): T {
    val token = auth.token(false)
    try {
        return block(token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HaException) {
        if (e.code != HA_AUTH_FAILED || auth.isFresh(token)) throw e
    }
    return block(auth.token(true))
}
