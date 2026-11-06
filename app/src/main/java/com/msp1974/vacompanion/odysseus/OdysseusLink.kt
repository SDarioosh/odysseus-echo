package com.msp1974.vacompanion.odysseus

// Odysseus fork: the device's own link to Odysseus, used to show what a voice turn created or
// completed. Home Assistant passes only the spoken answer to the satellite (Extended OpenAI
// Conversation drops everything else), so the device asks Odysseus directly:
//
//   GET <base>/actions?since=<epoch ms>&limit=<n>      Authorization: Bearer <voice token>
//   <base> is the same OpenAI base URL Home Assistant uses: https://<odysseus>/api/chat/voice/v1
//   -> {"actions": [VoiceAction, ...]}   newest first; see VoiceAction for the fields.
//   GET <base>/turns/latest?since=<epoch ms>           -> {"turn": VoiceTurn | null}  (VoiceTurnApi.kt)
//   POST <base>/satellite/turn (multipart)             -> device, area, wake word and the spoken command
//   POST <base>/satellite/interrupt (form: device_id)  -> a barge-in: end this device's turn now (BargeIn.kt)
//   GET <base>/today                                   -> the home screen's Today page (home/today/)
//   GET <base>/app/latest, GET <base>/app/apk          -> updates from the hub (odysseus/update/, openGet)
//   POST <base>/todos/done, /reminders/done, /action-items/done, /todos/undo, /action-items/undo
//                                                      -> Done buttons (odysseus/done/, hubRequest)
//

// The token is the same `voice`-scope token Home Assistant uses (read-only plus voice actions).
// It's stored encrypted with an Android Keystore key that never leaves the device.

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class VoiceAction(
    val id: String = "",
    /** ISO-8601 time the action was done. */
    val at: String = "",
    /** "created" or "completed". */
    val action: String = "created",
    /** "todo", "reminder", "list_item", "note", "event" or "action_item". */
    val kind: String = "todo",
    val title: String = "",
    val detail: String? = null,
    /** ISO-8601 due time or event start (a date only for all-day). */
    val due: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    /** Where it went, for display: "To-dos", "Shopping list", "Calendar", "Notes", "Athena"… */
    val where: String? = null,
    /** "odysseus" or "athena". */
    val source: String = "odysseus",
)

@Serializable
private data class VoiceActionsResponse(val actions: List<VoiceAction> = emptyList())

object OdysseusLink {
    private const val PREFS = "odysseus_link"
    private const val KEY_URL = "base_url"
    private const val KEY_TOKEN = "token_enc"
    private const val KEY_SHOW = "show_actions"
    private const val KEY_CHECK_VOICE = "check_voice"
    private const val KEY_THINKING_SOUND = "thinking_sound"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEYSTORE_ALIAS = "odysseus_link_token"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs()?.getString(KEY_URL, "") ?: ""
        set(value) { prefs()?.edit()?.putString(KEY_URL, value.trim().trimEnd('/'))?.apply() }

    var showActions: Boolean
        get() = prefs()?.getBoolean(KEY_SHOW, true) ?: true
        set(value) { prefs()?.edit()?.putBoolean(KEY_SHOW, value)?.apply() }

    /** Send the spoken command to Odysseus so Athena can check the speaker's voice (POST /satellite/turn). */
    var checkVoice: Boolean
        get() = prefs()?.getBoolean(KEY_CHECK_VOICE, true) ?: true
        set(value) { prefs()?.edit()?.putBoolean(KEY_CHECK_VOICE, value)?.apply() }

    /** Play a soft tone once when an answer takes longer than ThinkingChime.DELAY_MS. */
    var thinkingSound: Boolean
        get() = prefs()?.getBoolean(KEY_THINKING_SOUND, true) ?: true
        set(value) { prefs()?.edit()?.putBoolean(KEY_THINKING_SOUND, value)?.apply() }

    /**
     * The name Odysseus is told this device has ("Kitchen Echo"). Home Assistant never sends the
     * satellite its own device name, so it is set here; blank means the Wyoming name "VACA <id>".
     */
    var deviceName: String
        get() = prefs()?.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) { prefs()?.edit()?.putString(KEY_DEVICE_NAME, value.trim())?.apply() }

    val hasToken: Boolean get() = !prefs()?.getString(KEY_TOKEN, null).isNullOrEmpty()

    /** Changes whenever a token is saved (a hash of the stored, encrypted form; never the token). */
    val tokenStamp: Int get() = prefs()?.getString(KEY_TOKEN, null)?.hashCode() ?: 0

    val isConfigured: Boolean get() = baseUrl.startsWith("http") && hasToken

    fun setToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) {
            prefs()?.edit()?.remove(KEY_TOKEN)?.apply(); return
        }
        prefs()?.edit()?.putString(KEY_TOKEN, encrypt(t))?.apply()
    }

    /** The voice token, for the recorder's uploads to the same hub (odysseus/record/HubRelayTransport.kt). */
    internal fun bearerToken(): String? = token()

    private fun token(): String? = prefs()?.getString(KEY_TOKEN, null)?.let {
        runCatching { decrypt(it) }.onFailure { e -> Timber.w(e, "Odysseus token unreadable") }.getOrNull()
    }

    // --- Keystore ---------------------------------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    // --- API --------------------------------------------------------------------------------

    sealed interface Result {
        data class Ok(val actions: List<VoiceAction>) : Result
        data class Failed(val message: String) : Result
    }

    sealed interface TurnResult {
        /** The newest finished turn since the time asked, or null when there is none yet. */
        data class Ok(val turn: VoiceTurn?) : TurnResult
        /** The hub has no turn feed (404): an older Odysseus; use fetchActions instead. */
        data object NotSupported : TurnResult
        data class Failed(val message: String) : TurnResult
    }

    sealed interface PostResult {
        data class Ok(val response: SatelliteTurnResponse) : PostResult
        data class Failed(val message: String) : PostResult
    }

    internal class Reply(val code: Int, val body: String)

    private fun describe(code: Int, what: String) = when (code) {
        401, 403 -> "token refused ($code)"
        404 -> "Odysseus has no $what (404)"
        else -> "HTTP $code"
    }

    /**
     * One request to the hub; null body for a GET. Runs on the caller's dispatcher. The reply's
     * body is "" unless 2xx, or with [errorBody] also the start of an error answer (the Done
     * routes tell "not found" from "no such route" by it).
     */
    private fun request(
        path: String, contentType: String? = null, body: ByteArray? = null, readTimeoutMs: Int = 6000,
        errorBody: Boolean = false,
    ): Reply {
        val base = baseUrl
        val token = token()
        if (!base.startsWith("http") || token.isNullOrEmpty()) throw IllegalStateException("not set up")
        return send("$base$path", token, contentType, body, readTimeoutMs, errorBody)
    }

    /** [request] to a full [url]; split out so it is unit-tested against a local server. */
    internal fun send(
        url: String, token: String, contentType: String? = null, body: ByteArray? = null, readTimeoutMs: Int = 6000,
        errorBody: Boolean = false,
    ): Reply {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = readTimeoutMs
            // A redirect is answered as it is: following it would send the token to wherever it points.
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = when {
                code in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                errorBody -> runCatching { conn.errorStream?.bufferedReader()?.use { readAtMost(it, ERROR_BODY_MAX) } }.getOrNull().orEmpty()
                else -> ""
            }
            return Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }

    /** How much of an error answer [request] keeps (errorBody). */
    internal const val ERROR_BODY_MAX = 2000

    /** The first [max] chars of [reader], without reading (or waiting for) the rest. */
    private fun readAtMost(reader: java.io.Reader, max: Int): String {
        val buf = CharArray(max)
        var n = 0
        while (n < max) {
            val r = reader.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return String(buf, 0, n)
    }

    /**
     * A plain GET of [path] under the base URL with the voice token: (HTTP code, body; "" unless
     * 2xx). Blocking, on the caller's dispatcher. Throws when not linked or on network errors.
     */
    fun get(path: String, readTimeoutMs: Int = 6000): HaResponse =
        request(path, readTimeoutMs = readTimeoutMs).let { HaResponse(it.code, it.body) }

    /**
     * An authorised GET to the hub for a binary download (odysseus/update/), not yet connected:
     * the caller adds headers (Range), reads the stream and disconnects. Throws when not linked.
     */
    fun openGet(path: String, readTimeoutMs: Int = 30_000): HttpURLConnection {
        val base = baseUrl
        val token = token()
        if (!base.startsWith("http") || token.isNullOrEmpty()) throw IllegalStateException("not set up")
        return (URL("$base$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    suspend fun fetchActions(sinceEpochMs: Long, limit: Int = 8): Result = withContext(Dispatchers.IO) {
        try {
            val r = request("/actions?since=$sinceEpochMs&limit=$limit")
            if (r.code != 200) return@withContext Result.Failed(describe(r.code, "actions feed"))
            Result.Ok(json.decodeFromString<VoiceActionsResponse>(r.body).actions)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** GET /turns/latest: the newest finished voice turn that started at or after [sinceEpochMs]. */
    suspend fun fetchLatestTurn(sinceEpochMs: Long): TurnResult = withContext(Dispatchers.IO) {
        try {
            val r = request("/turns/latest?since=$sinceEpochMs")
            when (r.code) {
                200 -> TurnResult.Ok(VoiceTurnApi.parseLatestTurn(r.body))
                404 -> TurnResult.NotSupported
                else -> TurnResult.Failed(describe(r.code, "turn feed"))
            }
        } catch (e: Exception) {
            TurnResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * POST /satellite/turn: where and by which wake word this turn was asked, and, when
     * [audioWav] is given, the spoken command for Athena's speaker check. The hub forwards the
     * audio to Athena and never stores it.
     */
    suspend fun postSatelliteTurn(form: SatelliteTurnForm, audioWav: ByteArray?): PostResult = withContext(Dispatchers.IO) {
        try {
            val boundary = "odysseus-" + UUID.randomUUID().toString()
            val body = VoiceTurnApi.multipartBody(boundary, form.fields(), audioWav)
            val r = request("/satellite/turn", "multipart/form-data; boundary=$boundary", body, readTimeoutMs = 10_000)
            if (r.code !in 200..299) return@withContext PostResult.Failed(describe(r.code, "satellite route"))
            PostResult.Ok(VoiceTurnApi.parseSatelliteTurnResponse(r.body))
        } catch (e: Exception) {
            PostResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    sealed class InterruptResult {
        /** The hub cut [cut] running turns short and marked [marked] it hadn't been asked yet. */
        data class Ok(val cut: Int, val marked: Int) : InterruptResult()
        /** An older Odysseus without the route: the barge-in waits for the old answer as before. */
        object NotSupported : InterruptResult()
        data class Failed(val message: String) : InterruptResult()
    }

    /**
     * POST /satellite/interrupt: this device's wake word interrupted its turn, so Odysseus ends
     * that turn at once and Home Assistant can end its run (odysseus/BargeIn.kt).
     */
    suspend fun interruptTurn(deviceId: String): InterruptResult = withContext(Dispatchers.IO) {
        try {
            val body = "device_id=" + java.net.URLEncoder.encode(deviceId, "UTF-8")
            val r = request("/satellite/interrupt", "application/x-www-form-urlencoded", body.toByteArray(), readTimeoutMs = 4000)
            when (r.code) {
                200 -> VoiceTurnApi.parseInterruptResponse(r.body)
                404, 405 -> InterruptResult.NotSupported
                else -> InterruptResult.Failed(describe(r.code, "interrupt route"))
            }
        } catch (e: Exception) {
            InterruptResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    sealed class TimingResult {
        object Ok : TimingResult()
        /** An older Odysseus without the route. */
        object NotSupported : TimingResult()
        data class Failed(val message: String) : TimingResult()
    }

    /** POST /satellite/timing: when each stage of a finished turn happened ([TurnTimings.toJson]). */
    suspend fun postTimings(json: String): TimingResult = withContext(Dispatchers.IO) {
        try {
            val r = request("/satellite/timing", "application/json", json.toByteArray(), readTimeoutMs = 4000)
            when (r.code) {
                in 200..299 -> TimingResult.Ok
                404, 405 -> TimingResult.NotSupported
                else -> TimingResult.Failed(describe(r.code, "timing route"))
            }
        } catch (e: Exception) {
            TimingResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * A hub answer for callers that shape their own requests: the HTTP code and, for 2xx, the body
     * (asked with errorBody, also the start of an error answer).
     */
    data class HubReply(val code: Int, val body: String)

    /**
     * One request to `<base><path>` with the voice token (a GET without [body]), for features in
     * their own packages (announcements: odysseus/announce/; Done: odysseus/done/). Throws when
     * not linked or unreachable.
     */
    suspend fun hubRequest(
        path: String, contentType: String? = null, body: ByteArray? = null, readTimeoutMs: Int = 6000,
        errorBody: Boolean = false,
    ): HubReply =
        withContext(Dispatchers.IO) { request(path, contentType, body, readTimeoutMs, errorBody).let { HubReply(it.code, it.body) } }

    /** Tests the saved link: (ok, message to show). An older hub without the turn feed is tested with /actions. */
    suspend fun testLink(): Pair<Boolean, String> {
        val dayAgo = System.currentTimeMillis() - 86_400_000L
        return when (val t = fetchLatestTurn(dayAgo)) {
            is TurnResult.Ok -> true to "Linked: Odysseus answered"
            is TurnResult.Failed -> false to "Saved, but the test failed: ${t.message}"
            TurnResult.NotSupported -> when (val r = fetchActions(dayAgo, 1)) {
                is Result.Ok -> true to "Linked: Odysseus answered (an older Odysseus: actions only, no sources or voice check)"
                is Result.Failed -> false to "Saved, but the test failed: ${r.message}"
            }
        }
    }
}
