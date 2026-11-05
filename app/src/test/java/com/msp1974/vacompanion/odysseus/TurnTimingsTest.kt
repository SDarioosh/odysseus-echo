package com.msp1974.vacompanion.odysseus

import com.msp1974.vacompanion.satellite.PipelineStage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTimingsTest {
    private val wake = 1_762_199_530_000L

    @Test
    fun marksAreMillisecondsAfterTheWakeWord() {
        val t = TurnTimings(wake, "wake_word_detected")
        t.mark(PipelineStage.STARTED, wake + 120)
        t.mark(PipelineStage.VOICE_STOPPED, wake + 2600)
        t.mark(PipelineStage.STREAMING_TTS, wake + 9800)
        assertEquals(mapOf("started" to 120L, "voice_stopped" to 2600L, "streaming_tts" to 9800L), t.marks())
    }

    @Test
    fun aStageKeepsItsFirstTime() {
        val t = TurnTimings(wake, "wake_word_detected")
        t.mark(PipelineStage.LISTENING, wake + 100)
        t.mark(PipelineStage.LISTENING, wake + 900)
        assertEquals(100L, t.marks()["listening"])
    }

    @Test
    fun stagesBeforeHomeAssistantsRunAreLeftOut() {
        val t = TurnTimings(wake, "wake_word_detected")
        t.mark(PipelineStage.INIT, wake)
        t.mark(PipelineStage.STARTING, wake + 10)
        assertTrue(t.marks().isEmpty())
    }

    @Test
    fun timesAreKeptInTheRangeTheHubAccepts() {
        val t = TurnTimings(wake, "wake_word_detected")
        t.mark(PipelineStage.STARTED, wake - 50) // a clock step backwards
        t.mark(PipelineStage.ENDED, wake + 10 * TurnTimings.MAX_MS)
        assertEquals(0L, t.marks()["started"])
        assertEquals(TurnTimings.MAX_MS, t.marks()["ended"])
    }

    @Test
    fun theReportHasTheDeviceTheWakeWordTheMarksAndTheFlags() {
        val t = TurnTimings(wake, "continue_conversation")
        t.mark(PipelineStage.AWAITING_RESPONSE, wake + 3400)
        t.flag(TurnTimings.THINKING_SOUND)
        val body = Json.parseToJsonElement(t.toJson("echo-kitchen")).jsonObject
        assertEquals("echo-kitchen", body["device_id"]!!.jsonPrimitive.content)
        assertEquals(wake, body["started_at"]!!.jsonPrimitive.long)
        assertEquals("continue_conversation", body["start_mode"]!!.jsonPrimitive.content)
        assertEquals(3400L, body["marks"]!!.jsonObject["awaiting_response"]!!.jsonPrimitive.long)
        assertTrue(body["flags"]!!.jsonObject[TurnTimings.THINKING_SOUND]!!.jsonPrimitive.boolean)
        assertFalse(TurnTimings.BARGE_IN in body["flags"]!!.jsonObject)
    }
}
