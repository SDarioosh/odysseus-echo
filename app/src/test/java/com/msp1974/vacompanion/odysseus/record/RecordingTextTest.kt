package com.msp1974.vacompanion.odysseus.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTextTest {

    @Test
    fun elapsedTime() {
        assertEquals("0:00", RecordingText.elapsed(0))
        assertEquals("0:42", RecordingText.elapsed(42_900))
        assertEquals("12:34", RecordingText.elapsed((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", RecordingText.elapsed((3600 + 2 * 60 + 3) * 1000L))
        assertEquals("0:00", RecordingText.elapsed(-5))
    }

    @Test
    fun pill() {
        assertNull(RecordingText.pill(RecorderState.Idle, 0))
        assertEquals("REC 12:34", RecordingText.pill(RecorderState.Recording(1_000), 1_000 + 754_000))
        assertEquals("NOTE 0:42", RecordingText.pill(RecorderState.Note(1_000, false), 43_000))
    }

    @Test
    fun queue() {
        assertEquals("Nothing waiting to upload", RecordingText.queue(QueueStatus(0, 0, 0, 0)))
        assertEquals("1 chunk waiting (0.9 MB)", RecordingText.queue(QueueStatus(1, 960_044, 1, 0)))
        assertEquals(
            "3 chunks waiting (2.7 MB); 1 chunk refused by the hub, kept on the device",
            RecordingText.queue(QueueStatus(3, 2_880_132, 1, 1)),
        )
    }
}
