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
}

/**
 * Runs [block] with a token; when it throws HaException(code = 401), refreshes the token once and
 * runs it again. A second 401 is thrown to the caller: at most one retry per call, because
 * repeated 401s count toward Home Assistant's IP ban.
 */
suspend fun <T> withAuthRetry(auth: HaAuth, block: suspend (token: String) -> T): T {
    try {
        return block(auth.token(false))
    } catch (e: CancellationException) {
        throw e
    } catch (e: HaException) {
        if (e.code != 401) throw e
    }
    return block(auth.token(true))
}
