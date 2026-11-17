package com.msp1974.vacompanion.odysseus.home.weather

// Odysseus fork: the one process-wide holder of the clock page's weather, as Compose state.
// HomeHost calls refresh() every REFRESH_MS while the home screen is on, and again when Home
// Assistant reconnects or the setting changes. Any failure keeps the last reading for a while,
// then hides the badge; nothing else on the page depends on it.

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.ha.HaClient
import com.msp1974.vacompanion.odysseus.ha.HaException
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

object WeatherRepository {
    const val REFRESH_MS = 15 * 60_000L

    /** A reading older than this is dropped rather than shown. */
    private const val STALE_MS = 60 * 60_000L

    private val lock = Mutex()

    /** What the badge shows; null = hide it. */
    var weather by mutableStateOf<WeatherNow?>(null)
        private set

    /** Weather entities seen at the last refresh, for the settings menu. */
    var entities by mutableStateOf<List<WeatherEntity>>(emptyList())
        private set

    private var lastSuccessMs = 0L
    private var lastKey = ""

    /** Clears the badge at once, e.g. when the weather is turned off. */
    fun clear() {
        weather = null
        lastSuccessMs = 0L
    }

    /**
     * Fetches the weather through HaClient unless the last reading for the same setting is younger
     * than [minAgeMs]. State is written on the caller's thread (HomeHost: the main thread). Never
     * throws (except cancellation).
     */
    suspend fun refresh(deviceManager: DeviceManager, minAgeMs: Long = 0L) = lock.withLock {
        val wanted = HomeSettings.weatherEntity
        val now = System.currentTimeMillis()
        if (wanted == lastKey && weather != null && now - lastSuccessMs < minAgeMs) return@withLock
        try {
            val result = fetch(WeatherClient(HaClient.get(deviceManager)), wanted)
            weather = result
            lastSuccessMs = now
            lastKey = wanted
            Timber.i(
                "Weather: %s %s %s%s (H %s L %s)",
                result?.entityId ?: "none", result?.condition, result?.temperature, result?.unit,
                result?.high, result?.low,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Weather: refresh failed: %s", e.message)
            if (wanted != lastKey || now - lastSuccessMs > STALE_MS) weather = null
        }
    }

    private suspend fun fetch(client: WeatherClient, wanted: String): WeatherNow? {
        val list = client.entities()
        entities = list
        val entityId = when {
            wanted.isNotBlank() && WeatherClient.isWeatherEntityId(wanted) -> wanted
            else -> WeatherClient.autoPick(list)?.entityId ?: return null
        }
        val current = client.current(entityId) ?: return null
        val (high, low) = try {
            client.todayHighLow(entityId, LocalDate.now(), ZoneId.systemDefault())
        } catch (e: HaException) {
            Timber.w("Weather: no forecast: %s", e.message)
            null to null
        }
        return current.copy(high = high, low = low)
    }
}
