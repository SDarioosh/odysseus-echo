package com.msp1974.vacompanion.odysseus

// Odysseus fork: when each stage of one voice turn happened, in milliseconds after the wake word
// (docs/odysseus/turn-card.md, "Timings"). OdysseusTurn marks every pipeline stage here and, once
// the turn has ended, posts the report to Odysseus (POST <base>/satellite/timing), which joins it
// to its own times for the same turn: the hub sees its own steps, the device sees Home
// Assistant's speech to text and text to speech. With the turn's context id (the /satellite/turn
// answer) the hub joins them exactly, else by time. Only numbers, the device id, the context id
// and a few flags: never the question, the answer or audio.

import com.msp1974.vacompanion.satellite.PipelineStage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class TurnTimings(private val startedAtMs: Long, private val startMode: String) {
    private val marks = LinkedHashMap<String, Long>()
    private val flags = LinkedHashMap<String, Boolean>()

    /** The first time [stage] was reached; INIT and STARTING (before HA's run) aren't kept. */
    @Synchronized
    fun mark(stage: PipelineStage, nowMs: Long) {
        if (stage == PipelineStage.INIT || stage == PipelineStage.STARTING) return
        val name = stage.name.lowercase()
        if (name !in marks) marks[name] = (nowMs - startedAtMs).coerceIn(0, MAX_MS)
    }

    @Synchronized
    fun flag(name: String, value: Boolean = true) {
        flags[name] = value
    }

    @Synchronized
    fun marks(): Map<String, Long> = LinkedHashMap(marks)

    /** The JSON body of POST /satellite/timing; [contextId] (the turn's, when known) lets the hub join it exactly. */
    @Synchronized
    fun toJson(deviceId: String, contextId: String? = null): String = buildJsonObject {
        put("device_id", deviceId)
        if (!contextId.isNullOrEmpty()) put("context_id", contextId)
        put("started_at", startedAtMs)
        put("start_mode", startMode)
        putJsonObject("marks") { marks.forEach { (k, v) -> put(k, v) } }
        putJsonObject("flags") { flags.forEach { (k, v) -> put(k, v) } }
    }.toString()

    companion object {
        /** The hub refuses marks past an hour; a turn never lasts that long. */
        const val MAX_MS = 3_600_000L
        const val THINKING_SOUND = "thinking_sound"
        const val BARGE_IN = "barge_in"
        const val VOICE_CHECK = "voice_check"
    }
}
