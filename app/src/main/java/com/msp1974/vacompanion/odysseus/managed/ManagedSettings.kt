package com.msp1974.vacompanion.odysseus.managed

// Odysseus fork: settings Odysseus manages for this device (plan contract C6,
// docs/odysseus/managed-settings.md). The process-wide face of ManagedEngine:
//   - register(): how to read and write a setting's store (ManagedBindings.kt registers every
//     field a store declares with `managed = …`, settings/PrefStore.kt);
//   - isManaged(): Compose state for the settings screen ("Set by Odysseus", control disabled);
//   - start()/fetchNow(): the hub is asked at start, after each hello and on a "settings" item of
//     the announcements feed (odysseus/announce/Announcements.kt).
// All engine work runs on the main thread (the stores are Compose state).

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.msp1974.vacompanion.odysseus.OdysseusLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

object ManagedSettings {
    /** Retries after a failed fetch (network, server error); hello asks again hourly anyway. */
    private val RETRY_MS = listOf(60_000L, 5 * 60_000L, 15 * 60_000L)

    private val main = Handler(Looper.getMainLooper())
    private var engine: ManagedEngine? = null
    private val pending = LinkedHashMap<String, ManagedBinding>()
    private var managedKeysState by mutableStateOf(emptySet<String>())

    private var scope: CoroutineScope? = null
    private var deviceId: () -> String = { "" }
    private val wake = Channel<String>(Channel.CONFLATED)

    /**
     * Makes [key] manageable by Odysseus. [get] reads the store's value, [set] writes it (the same
     * setter the settings screen uses). Values are Boolean, Int or String as in C6's table. Call
     * once, at any time: a value Odysseus already set is applied at once.
     */
    fun register(key: String, get: () -> Any, set: (Any) -> Unit) = onMain {
        val binding = ManagedBinding(key, get, set)
        val e = engine
        if (e == null) pending[key] = binding else { e.register(binding); publish(e) }
    }

    /** True while Odysseus sets [key]: the settings screen shows it and disables the control. Compose state. */
    fun isManaged(key: String): Boolean = key in managedKeysState

    /** Loads the last applied settings (offline too) and starts asking the hub. Safe to call again. */
    @Synchronized
    fun start(context: Context, deviceId: () -> String) {
        if (scope != null) return
        val app = context.applicationContext
        OdysseusLink.init(app)
        this.deviceId = deviceId
        onMain {
            if (engine == null) {
                val e = ManagedEngine(PrefsManagedStore(app)) { Timber.w(it) }
                engine = e
                ManagedBindings.registerBuiltIns(app)
                pending.values.forEach { e.register(it) }
                pending.clear()
                e.reapply()
                publish(e)
                if (e.managedKeys.isNotEmpty()) {
                    Timber.i("Managed settings: version ${e.version}, set by Odysseus: ${e.managedKeys.sorted().joinToString()}")
                }
            }
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { run() }
        fetchNow("start")
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** Ask the hub now ([why] is for the log: "start", "hello", "feed"). */
    fun fetchNow(why: String) {
        wake.trySend(why)
    }

    /**
     * The owner unlinked this device (Menu → Odysseus → Unlink): no hub manages it any more, so
     * every managed setting goes back to the device's own value and its control is enabled again.
     */
    fun releaseAll(why: String) = onMain {
        val e = engine ?: return@onMain
        val diff = e.releaseAll()
        publish(e)
        if (!diff.isEmpty) Timber.i("Managed settings ($why): $diff")
    }

    private suspend fun run() {
        while (scope?.isActive == true) {
            var why = wake.receive()
            var attempt = 0
            while (true) {
                val ok = try {
                    fetch(why)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("Managed settings: ${e.message ?: e.javaClass.simpleName}")
                    false
                }
                if (ok || attempt >= RETRY_MS.size) break
                // A new request (hello, feed) cuts the wait short.
                why = withTimeoutOrNull(RETRY_MS[attempt]) { wake.receive() } ?: "retry"
                attempt++
            }
        }
    }

    /** True when there is nothing to retry (applied, not linked or refused). */
    private suspend fun fetch(why: String): Boolean {
        val id = deviceId()
        if (!OdysseusLink.isConfigured || id.isBlank()) return true
        val reply = OdysseusLink.hubRequest(ManagedKeys.path(id))
        when (reply.code) {
            200 -> {
                val parsed = ManagedKeys.parse(reply.body)
                if (parsed == null) {
                    Timber.w("Managed settings: unreadable answer: ${reply.body.take(200)}")
                    return true
                }
                withContext(Dispatchers.Main) {
                    val e = engine ?: return@withContext
                    val diff = e.accept(parsed)
                    publish(e)
                    if (!diff.isEmpty) Timber.i("Managed settings: version ${parsed.version} ($why): $diff")
                    else Timber.d("Managed settings: version ${parsed.version} ($why): no change")
                }
                return true
            }
            401, 403 -> Timber.w("Managed settings: token refused (${reply.code}); keeping the last settings")
            else -> {
                Timber.w("Managed settings: answered HTTP ${reply.code}")
                return false
            }
        }
        return true
    }

    private fun publish(e: ManagedEngine) {
        managedKeysState = e.managedKeys
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/** The engine's state in SharedPreferences (`odysseus_managed`). */
private class PrefsManagedStore(context: Context) : ManagedStore {
    private val prefs = context.getSharedPreferences("odysseus_managed", Context.MODE_PRIVATE)

    override var version: Long?
        get() = prefs.getLong("version", -1L).takeIf { it >= 0 }
        set(value) = prefs.edit { if (value == null) remove("version") else putLong("version", value) }

    override var values: Map<String, Any>
        get() = read("values")
        set(value) = write("values", value)

    override var own: Map<String, Any>
        get() = read("own")
        set(value) = write("own", value)

    private fun read(name: String): Map<String, Any> {
        val obj = runCatching { Json.parseToJsonElement(prefs.getString(name, "{}") ?: "{}") as? JsonObject }
            .getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> ManagedKeys.decodeStored(k, v)?.let { k to it } }.toMap()
    }

    private fun write(name: String, map: Map<String, Any>) {
        val obj = JsonObject(map.mapNotNull { (k, v) ->
            ManagedKeys.all[k]?.let { spec -> k to (spec.toJson(v) as JsonElement) }
        }.toMap())
        prefs.edit { putString(name, obj.toString()) }
    }
}
