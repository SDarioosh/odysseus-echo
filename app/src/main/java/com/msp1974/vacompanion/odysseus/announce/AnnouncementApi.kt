package com.msp1974.vacompanion.odysseus.announce

// Odysseus fork: the hub's device announcements feed and the satellite hello (plan contract C3).
// Pure: JSON parsing and form bodies, unit-tested. The requests themselves are in Announcements.kt.
//
//   GET  <base>/announcements?device_id=<id>&since=<epoch ms>
//        -> {"announcements": [{"id", "at", "kind", "title", "text", "target", "ref", "expires_at"}]}
//   POST <base>/satellite/hello   form: device_id, device_name, area, app_version_code -> {"ok": true}
//
// <base> is the voice base URL (OdysseusLink.baseUrl): https://<odysseus>/api/chat/voice/v1

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

@Serializable
data class Announcement(
    val id: String = "",
    /** Epoch ms the hub made it (the hub's clock). */
    val at: Long = 0L,
    /** "reminder", "announcement" or "settings" (this device's settings changed: no card, see isSettings). */
    val kind: String = KIND_ANNOUNCEMENT,
    val title: String = "",
    val text: String = "",
    /** Who it was for ({"area": …}, {"device_id": …} or "all"); shown nowhere, kept for logs. */
    val target: JsonElement? = null,
    /** The to-do it reminds of ("todo:41:3"), if any. */
    val ref: String? = null,
    /** Epoch ms after which it is no longer worth showing; null: no end of its own. */
    @SerialName("expires_at") val expiresAt: Long? = null,
) {
    val isReminder: Boolean get() = kind == KIND_REMINDER

    /** Odysseus changed this device's settings (C6): fetch them, show nothing (odysseus/managed/). */
    val isSettings: Boolean get() = kind == KIND_SETTINGS

    /** What the card shows as its heading: the title, else the text. */
    val heading: String get() = title.ifBlank { text }

    /** A reminder of a to-do: its card has a Done button (reminders/done, ReminderDone.kt). */
    val canMarkDone: Boolean get() = isReminder && !ref.isNullOrBlank()

    /** The card's second line, unless it only repeats the heading. */
    val body: String? get() = text.takeIf { it.isNotBlank() && it.trim() != heading.trim() }

    companion object {
        const val KIND_REMINDER = "reminder"
        const val KIND_ANNOUNCEMENT = "announcement"
        const val KIND_SETTINGS = "settings"
    }
}

@Serializable
private data class AnnouncementsResponse(val announcements: List<Announcement> = emptyList())

@Serializable
private data class HelloResponse(val ok: Boolean = false)

/** What this device tells the hub about itself (POST /satellite/hello). */
data class HelloForm(
    val deviceId: String,
    val deviceName: String,
    val area: String,
    val appVersionCode: Int,
) {
    fun fields(): List<Pair<String, String>> = listOf(
        "device_id" to deviceId,
        "device_name" to deviceName,
        "area" to area,
        "app_version_code" to appVersionCode.toString(),
    )

    companion object {
        /** A blank device name is the Wyoming name, as in the satellite post (OdysseusTurn). */
        fun of(deviceId: String, deviceName: String, area: String, appVersionCode: Int) =
            HelloForm(deviceId, deviceName.trim().ifBlank { "VACA $deviceId" }, area.trim(), appVersionCode)
    }
}

object AnnouncementApi {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    fun feedPath(deviceId: String, sinceEpochMs: Long): String =
        "/announcements?device_id=${enc(deviceId)}&since=$sinceEpochMs"

    const val HELLO_PATH = "/satellite/hello"

    /** The feed's announcements; ones without an id are dropped (they couldn't be shown once only). */
    fun parseFeed(body: String): List<Announcement> =
        json.decodeFromString<AnnouncementsResponse>(body.ifBlank { "{}" }).announcements.filter { it.id.isNotBlank() }

    fun parseHello(body: String): Boolean =
        runCatching { json.decodeFromString<HelloResponse>(body.ifBlank { "{}" }).ok }.getOrDefault(false)

    fun formBody(fields: List<Pair<String, String>>): String =
        fields.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
