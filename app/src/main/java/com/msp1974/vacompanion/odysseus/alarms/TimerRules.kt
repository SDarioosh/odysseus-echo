package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: this device's timers (docs/odysseus/alarms-timers.md). Pure, unit-tested.
//
// Timers are Home Assistant's own voice timers ("set a 10 minute pasta timer"). HA keeps them and
// sends the satellite that set one these Wyoming events (its wyoming integration, which the VACA
// integration builds on):
//   timer-started   {id, total_seconds, name?, start_hours?, start_minutes?, start_seconds?}
//   timer-updated   {id, is_active, total_seconds}    paused, resumed, time added or taken off
//   timer-cancelled {id}
//   timer-finished  {id}
// `total_seconds` is what is left at that moment (HA resets its clock on every change), so the
// device keeps its own countdown from it and rings at its own end time: HA drops `timer-finished`
// for a satellite that isn't connected then. Timers are per device: only the device that set one
// shows and rings it (HA's own lookups still find it from any device: "cancel the pasta timer").

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class DeviceTimer(
    val id: String,
    /** The name said ("pasta"), or null. */
    val name: String? = null,
    /** How long it was set for, in seconds (the label: "10 min"). */
    val setSeconds: Long,
    /** Epoch ms it ends while running; null while paused. */
    val endsAtMs: Long? = null,
    /** Ms left while paused. */
    val pausedLeftMs: Long = 0L,
) {
    val running: Boolean get() = endsAtMs != null

    fun leftMs(nowMs: Long): Long = endsAtMs?.let { (it - nowMs).coerceAtLeast(0L) } ?: pausedLeftMs
}

object TimerRules {
    const val STARTED = "timer-started"
    const val UPDATED = "timer-updated"
    const val CANCELLED = "timer-cancelled"
    const val FINISHED = "timer-finished"
    val EVENTS = setOf(STARTED, UPDATED, CANCELLED, FINISHED)

    /** More would not fit on the screen; HA would not normally have that many. */
    const val MAX = 10

    /** What an event did. [finished]: the timer HA says has ended (ring it unless already rung). */
    data class Result(val timers: List<DeviceTimer>, val finished: DeviceTimer? = null)

    /** [timers] after the Wyoming event [type] with [data], at [nowMs]. Unknown ids are ignored. */
    fun apply(timers: List<DeviceTimer>, type: String, data: JsonObject, nowMs: Long): Result {
        val id = data.str("id") ?: return Result(timers)
        val existing = timers.firstOrNull { it.id == id }
        return when (type) {
            STARTED -> {
                val total = data.long("total_seconds")?.coerceAtLeast(0L) ?: return Result(timers)
                val set = (data.long("start_hours") ?: 0L) * 3600 + (data.long("start_minutes") ?: 0L) * 60 +
                    (data.long("start_seconds") ?: 0L)
                val t = DeviceTimer(
                    id = id,
                    name = data.str("name")?.trim()?.takeIf { it.isNotEmpty() },
                    setSeconds = if (set > 0) set else total,
                    endsAtMs = nowMs + total * 1000,
                )
                Result((timers.filter { it.id != id } + t).sortedBy { it.leftMs(nowMs) }.take(MAX))
            }
            UPDATED -> {
                existing ?: return Result(timers)
                val left = (data.long("total_seconds") ?: return Result(timers)).coerceAtLeast(0L) * 1000
                val active = data.bool("is_active") ?: true
                val t = if (active) existing.copy(endsAtMs = nowMs + left, pausedLeftMs = 0L)
                else existing.copy(endsAtMs = null, pausedLeftMs = left)
                Result(timers.map { if (it.id == id) t else it })
            }
            CANCELLED -> Result(timers.filter { it.id != id })
            FINISHED -> Result(timers.filter { it.id != id }, existing ?: DeviceTimer(id, null, 0L, nowMs))
            else -> Result(timers)
        }
    }

    /** Running timers that have ended by [nowMs]. */
    fun ended(timers: List<DeviceTimer>, nowMs: Long): List<DeviceTimer> =
        timers.filter { it.endsAtMs != null && it.endsAtMs <= nowMs }

    /** The countdown: "4:59", "1:02:03". Rounded up, so it shows 0:00 only at the end. */
    fun countdown(leftMs: Long): String {
        val s = (leftMs + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "Pasta", else "10 min timer", "1 h 30 min timer", "45 s timer". */
    fun title(t: DeviceTimer): String = t.name?.replaceFirstChar { it.uppercase() } ?: "${duration(t.setSeconds)} timer"

    /** "10 min", "1 h 30 min", "45 s", "2 min 30 s". */
    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it h" },
            m.takeIf { it > 0 }?.let { "$it min" },
            s.takeIf { it > 0 || seconds == 0L }?.let { "$it s" },
        ).joinToString(" ")
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
}
