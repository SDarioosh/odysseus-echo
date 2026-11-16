package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: this device's alarms (docs/odysseus/alarms-timers.md). Pure: the hub's alarm list,
// when each alarm rings next and how it is labelled; unit-tested on the JVM. Alarms are set by voice
// through Odysseus (Home Assistant has no alarm intent) and kept per device by the hub:
//
//   GET    <base>/alarms?device_id=<id>
//          -> {"alarms": [{"id": "al_…", "time": "07:30", "days": [1,2,3,4,5], "date": null,
//                          "label": "Gym" | null}], "updated_at": "<iso>"}
//   DELETE <base>/alarms/<alarm id>?device_id=<id>   -> {"ok": true} (idempotent)
//
// `time` is a wall-clock time, rung in the device's own time zone. `days` are ISO weekdays
// (Monday = 1 … Sunday = 7) for a repeating alarm, [] for a one-off, which has a `date` instead.

import com.msp1974.vacompanion.odysseus.HubJson
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Serializable
data class HubAlarm(
    val id: String = "",
    /** "HH:MM", 24 h. */
    val time: String = "",
    val days: List<Int> = emptyList(),
    /** "YYYY-MM-DD" for a one-off. */
    val date: String? = null,
    val label: String? = null,
)

@Serializable
private data class AlarmsResponse(val alarms: List<HubAlarm> = emptyList())

/** One alarm of this device, checked: a valid time, and a date for a one-off. */
data class Alarm(
    val id: String,
    val time: LocalTime,
    /** ISO weekdays it repeats on; empty for a one-off. */
    val days: Set<Int>,
    /** The day a one-off rings; null for a repeating alarm. */
    val date: LocalDate?,
    val label: String?,
) {
    val repeats: Boolean get() = days.isNotEmpty()
}

object AlarmRules {
    const val PATH = "/alarms"

    fun listPath(deviceId: String): String = "$PATH?device_id=" + enc(deviceId)

    fun deletePath(alarmId: String, deviceId: String): String =
        "$PATH/" + enc(alarmId) + "?device_id=" + enc(deviceId)

    /** The alarms in a GET /alarms body, invalid ones left out. Throws on a body that isn't JSON. */
    fun parse(body: String): List<Alarm> =
        HubJson.decodeFromString<AlarmsResponse>(body).alarms.mapNotNull { of(it) }

    /** [h] as an [Alarm], or null when its id or time is missing or wrong, or a one-off has no date. */
    fun of(h: HubAlarm): Alarm? {
        if (h.id.isBlank()) return null
        val time = runCatching { LocalTime.parse(h.time.trim()) }.getOrNull() ?: return null
        val days = h.days.filter { it in 1..7 }.toSortedSet()
        val date = h.date?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
        if (days.isEmpty() && date == null) return null
        return Alarm(h.id, time.withSecond(0).withNano(0), days, if (days.isEmpty()) date else null,
            h.label?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Back to the hub's shape (the device keeps its last list across restarts). */
    fun toHub(a: Alarm): HubAlarm = HubAlarm(
        a.id, "%02d:%02d".format(a.time.hour, a.time.minute), a.days.toList(), a.date?.toString(), a.label,
    )

    fun encode(alarms: List<Alarm>): String =
        HubJson.encodeToString(AlarmsResponse.serializer(), AlarmsResponse(alarms.map { toHub(it) }))

    /**
     * The first time [a] rings strictly after [after], in [zone]; null for a one-off that has
     * passed. A time skipped by a clock change (spring forward) rings at the time it moves to.
     */
    fun nextRing(a: Alarm, after: ZonedDateTime, zone: ZoneId = after.zone): ZonedDateTime? {
        val from = after.withZoneSameInstant(zone)
        if (!a.repeats) {
            val at = ZonedDateTime.of(a.date!!, a.time, zone)
            return at.takeIf { it.isAfter(from) }
        }
        var day = from.toLocalDate()
        repeat(8) {
            if (day.dayOfWeek.value in a.days) {
                val at = ZonedDateTime.of(day, a.time, zone)
                if (at.isAfter(from)) return at
            }
            day = day.plusDays(1)
        }
        return null
    }

    /** "Weekdays", "Weekends", "Every day", "Mon, Wed, Fri"; null for a one-off. */
    fun daysLabel(days: Set<Int>, locale: Locale = Locale.getDefault()): String? = when {
        days.isEmpty() -> null
        days.size == 7 -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(6, 7) -> "Weekends"
        else -> days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
    }

    /** "7:30 AM". */
    fun timeLabel(t: LocalTime, locale: Locale = Locale.getDefault()): String =
        t.format(DateTimeFormatter.ofPattern("h:mm a", locale))

    /** What the menu shows under an alarm: "Weekdays", or the one-off's "Tomorrow" / "Fri 21 Nov". */
    fun whenLabel(a: Alarm, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        daysLabel(a.days, locale) ?: when (val d = a.date!!) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
