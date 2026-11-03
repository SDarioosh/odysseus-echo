package com.msp1974.vacompanion.odysseus

// Odysseus fork: the device's own link to Odysseus, used to show what a voice turn created or
// completed. Home Assistant passes only the spoken answer to the satellite (Extended OpenAI
// Conversation drops everything else), so the device asks Odysseus directly:
//
//   GET <base>/actions?since=<epoch ms>&limit=<n>      Authorization: Bearer <voice token>
//   <base> is the same OpenAI base URL Home Assistant uses: https://<odysseus>/api/chat/voice/v1
//   -> {"actions": [VoiceAction, ...]}   newest first; see VoiceAction for the fields.
//
// The token is the same `voice`-scope token Home Assistant uses (read-only plus voice actions).
// It's stored encrypted with an Android Keystore key that never leaves the device.

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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

    val hasToken: Boolean get() = !prefs()?.getString(KEY_TOKEN, null).isNullOrEmpty()

    val isConfigured: Boolean get() = baseUrl.startsWith("http") && hasToken

    fun setToken(token: String) {
        val t = token.trim()
        if (t.isEmpty()) {
            prefs()?.edit()?.remove(KEY_TOKEN)?.apply(); return
        }
        prefs()?.edit()?.putString(KEY_TOKEN, encrypt(t))?.apply()
    }

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

    suspend fun fetchActions(sinceEpochMs: Long, limit: Int = 8): Result = withContext(Dispatchers.IO) {
        val base = baseUrl
        val token = token()
        if (!base.startsWith("http") || token.isNullOrEmpty()) return@withContext Result.Failed("not set up")
        try {
            val conn = URL("$base/actions?since=$sinceEpochMs&limit=$limit").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            try {
                val code = conn.responseCode
                if (code != 200) return@withContext Result.Failed(
                    when (code) { 401, 403 -> "token refused ($code)"; 404 -> "Odysseus has no actions feed (404)"; else -> "HTTP $code" }
                )
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Result.Ok(json.decodeFromString<VoiceActionsResponse>(body).actions)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
