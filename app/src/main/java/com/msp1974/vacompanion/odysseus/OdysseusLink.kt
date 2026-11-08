package com.msp1974.vacompanion.odysseus

// Odysseus fork: the device's own link to Odysseus, used to show what a voice turn created or
// completed. Home Assistant passes only the spoken answer to the satellite (Extended OpenAI
// Conversation drops everything else), so the device asks Odysseus directly:
//
//   <base> is the same OpenAI base URL Home Assistant uses: https://<odysseus>/api/chat/voice/v1,
//   every request with Authorization: Bearer <voice token>.
//   GET <base>/turns/latest?context_id=<id> | ?since=<epoch ms> -> {"turn": VoiceTurn | null}  (VoiceTurnApi.kt)
//   POST <base>/satellite/turn (multipart)             -> device, area, wake word and the spoken command;
//                                                         answers the turn's context id
//   POST <base>/satellite/interrupt (form: device_id, context_id?) -> a barge-in: end that turn now (BargeIn.kt)
//   POST <base>/satellite/timing (JSON, context_id?)   -> the turn's stage times (TurnTimings.kt)
//   GET <base>/today                                   -> the home screen's Today page (home/today/)
//   GET <base>/app/latest, GET <base>/app/apk          -> updates from the hub (odysseus/update/; the APK by openGet)
//   POST <base>/todos/done, /reminders/done, /action-items/done, /todos/undo, /action-items/undo
//                                                      -> Done buttons (odysseus/done/, through HubHttp)
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
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One thing a voice turn created or completed (VoiceTurn.actions). */
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

object OdysseusLink : HubHttp {
    private const val PREFS = "odysseus_link"
    private const val KEY_URL = "base_url"
    private const val KEY_TOKEN = "token_enc"
    private const val KEY_SHOW = "show_actions"
    private const val KEY_CHECK_VOICE = "check_voice"
    private const val KEY_THINKING_SOUND = "thinking_sound"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEYSTORE_ALIAS = "odysseus_link_token"

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

    /**
     * A token is saved and can be read. One that can't be decrypted (its Keystore key is gone, e.g.
     * after the app's data was moved to another device) counts as none: the device shows "Not
     * linked" and sends nothing until a token is typed in again.
     */
    val hasToken: Boolean get() = token() != null

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

    private val tokenMemo = TokenMemo({ decrypt(it) }) { e -> Timber.w(e, "Odysseus token unreadable; link again in Menu → Odysseus") }

    private fun token(): String? = tokenMemo.read(prefs()?.getString(KEY_TOKEN, null))

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

    /**
     * A failed request for logs and the settings screen. An answer that can't be read says only
     * so: the JSON parser's message quotes the answer, which holds the owner's words (turn
     * titles, spoken answers, announcements), never logged.
     */
    fun failureText(e: Throwable): String = when (e) {
        is kotlinx.serialization.SerializationException, is IllegalArgumentException ->
            "unreadable answer (${e.javaClass.simpleName})"
        else -> e.message ?: e.javaClass.simpleName
    }

    sealed interface TurnResult {
        /** The newest finished turn since the time asked, or null when there is none yet. */
        data class Ok(val turn: VoiceTurn?) : TurnResult
        data class Failed(val message: String) : TurnResult
    }

    sealed interface PostResult {
        data class Ok(val response: SatelliteTurnResponse) : PostResult
        data class Failed(val message: String) : PostResult
    }

    /** How much of an error answer [send] keeps (errorBody). */
    internal const val ERROR_BODY_MAX = 2000

    /**
     * The one hub request: [url] with the Bearer [token], a GET without [body]. Blocking, on the
     * caller's thread; throws on network errors. The reply's body is at most [maxBodyChars] of a
     * 2xx answer, else "" (with [errorBody] the first ERROR_BODY_MAX chars of the error answer).
     * Unit-tested against a local server (OdysseusLinkHttpTest).
     */
    internal fun send(
        url: String, token: String, contentType: String? = null, body: ByteArray? = null,
        readTimeoutMs: Int = 6000, connectTimeoutMs: Int = 4000, maxBodyChars: Int = Int.MAX_VALUE,
        errorBody: Boolean = false,
    ): HubReply {
        val conn = open(url, token, connectTimeoutMs, readTimeoutMs)
        try {
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
                code in 200..299 -> conn.inputStream.bufferedReader().use { readAtMost(it, maxBodyChars) }
                errorBody -> runCatching {
                    conn.errorStream?.bufferedReader()?.use { readAtMost(it, minOf(ERROR_BODY_MAX, maxBodyChars)) }
                }.getOrNull().orEmpty()
                else -> ""
            }
            return HubReply(code, text, conn.date.takeIf { it > 0 })
        } finally {
            conn.disconnect()
        }
    }

    /** [send] to `<base><path>` with the voice token. Blocking; throws when not linked. */
    internal fun sendToHub(
        path: String, contentType: String? = null, body: ByteArray? = null,
        readTimeoutMs: Int = 6000, connectTimeoutMs: Int = 4000, maxBodyChars: Int = Int.MAX_VALUE,
        errorBody: Boolean = false,
    ): HubReply {
        val (base, token) = link()
        return send("$base$path", token, contentType, body, readTimeoutMs, connectTimeoutMs, maxBodyChars, errorBody)
    }

    /** The [HubHttp] of the device: [call] under the base URL, on the IO dispatcher. */
    override suspend fun request(call: HubCall): HubReply = withContext(Dispatchers.IO) {
        sendToHub(call.path, call.contentType, call.body, call.readTimeoutMs, errorBody = call.errorBody)
    }

    /**
     * An authorised GET to the hub for the streamed APK download (odysseus/update/), not yet
     * connected: the caller adds headers (Range), reads the stream and disconnects. Throws when
     * not linked.
     */
    fun openGet(path: String, readTimeoutMs: Int = 30_000): HttpURLConnection {
        val (base, token) = link()
        return open("$base$path", token, connectTimeoutMs = 10_000, readTimeoutMs = readTimeoutMs)
    }

    /** The base URL and the token, or throws when not linked. */
    private fun link(): Pair<String, String> {
        val base = baseUrl
        val token = token()
        if (!base.startsWith("http") || token.isNullOrEmpty()) throw IllegalStateException("not set up")
        return base to token
    }

    private fun open(url: String, token: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            // A redirect is answered as it is: following it would send the token to wherever it points.
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
        }

    /** The first [max] chars of [reader], without reading (or waiting for) the rest. */
    private fun readAtMost(reader: java.io.Reader, max: Int): String {
        val out = StringBuilder()
        val buf = CharArray(8192)
        while (out.length < max) {
            val n = reader.read(buf, 0, minOf(buf.size, max - out.length))
            if (n < 0) break
            out.appendRange(buf, 0, n)
        }
        return out.toString()
    }

    /**
     * GET /turns/latest: the finished turn of [contextId] when known (the /satellite/turn answer),
     * else the newest finished voice turn that started at or after [sinceEpochMs].
     */
    suspend fun fetchLatestTurn(contextId: String?, sinceEpochMs: Long, http: HubHttp = this): TurnResult = try {
        val r = http.request(HubCall(VoiceTurnApi.latestTurnPath(contextId, sinceEpochMs)))
        if (r.code != 200) TurnResult.Failed(hubStatusText(r.code))
        else TurnResult.Ok(VoiceTurnApi.parseLatestTurn(r.body))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TurnResult.Failed(failureText(e))
    }

    /**
     * POST /satellite/turn: where and by which wake word this turn was asked, and, when
     * [audioWav] is given, the spoken command for Athena's speaker check. The hub forwards the
     * audio to Athena and never stores it.
     */
    suspend fun postSatelliteTurn(form: SatelliteTurnForm, audioWav: ByteArray?): PostResult = try {
        val boundary = "odysseus-" + UUID.randomUUID().toString()
        val body = VoiceTurnApi.multipartBody(boundary, form.fields(), audioWav)
        val r = request(HubCall("/satellite/turn", "multipart/form-data; boundary=$boundary", body, readTimeoutMs = 10_000))
        if (r.code !in 200..299) PostResult.Failed(hubStatusText(r.code))
        else PostResult.Ok(VoiceTurnApi.parseSatelliteTurnResponse(r.body))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PostResult.Failed(failureText(e))
    }

    sealed class InterruptResult {
        /** The hub cut [cut] running turns short and marked [marked] it hadn't been asked yet. */
        data class Ok(val cut: Int, val marked: Int) : InterruptResult()
        data class Failed(val message: String) : InterruptResult()
    }

    /**
     * POST /satellite/interrupt: this device's wake word interrupted its turn, so Odysseus ends
     * that turn at once and Home Assistant can end its run (odysseus/BargeIn.kt). With
     * [contextId] (the interrupted turn's) only that turn is cut; without, this device's turns.
     */
    suspend fun interruptTurn(deviceId: String, contextId: String?, http: HubHttp = this): InterruptResult = try {
        val body = VoiceTurnApi.interruptForm(deviceId, contextId)
        val r = http.request(HubCall("/satellite/interrupt", "application/x-www-form-urlencoded", body.toByteArray(), readTimeoutMs = 4000))
        if (r.code != 200) InterruptResult.Failed(hubStatusText(r.code))
        else VoiceTurnApi.parseInterruptResponse(r.body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        InterruptResult.Failed(failureText(e))
    }

    /**
     * POST /satellite/timing: when each stage of a finished turn happened ([TurnTimings.toJson]).
     * True when the hub took them; a failure is logged here.
     */
    suspend fun postTimings(json: String): Boolean {
        val failure = try {
            val r = request(HubCall("/satellite/timing", "application/json", json.toByteArray(), readTimeoutMs = 4000))
            if (r.code in 200..299) null else hubStatusText(r.code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failureText(e)
        }
        failure?.let { Timber.w("Odysseus turn timings: $it") }
        return failure == null
    }

    /** Tests the saved link with the turn feed: (ok, message to show). */
    suspend fun testLink(): Pair<Boolean, String> =
        when (val t = fetchLatestTurn(null, System.currentTimeMillis() - 86_400_000L)) {
            is TurnResult.Ok -> true to "Linked: Odysseus answered"
            is TurnResult.Failed -> false to "Saved, but the test failed: ${t.message}"
        }
}

/**
 * The decrypted token, kept for the stored (encrypted) value it came from, so the Keystore is
 * asked once per saved token, not on every request and every `hasToken`. A value that can't be
 * decrypted is tried again only after [retryMs] (or at once when a new one is saved), so it
 * doesn't log a stack trace on every request.
 */
internal class TokenMemo(
    private val decrypt: (String) -> String,
    private val retryMs: Long = 10 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private var stored: String? = null
    private var plain: String? = null
    private var failedAt: Long? = null

    @Synchronized
    fun read(enc: String?): String? {
        if (enc.isNullOrEmpty()) return null
        val failed = failedAt
        if (enc == stored && (failed == null || clock() - failed < retryMs)) return plain
        stored = enc
        plain = runCatching { decrypt(enc) }.onFailure(onFailure).getOrNull()
        failedAt = if (plain == null) clock() else null
        return plain
    }
}
