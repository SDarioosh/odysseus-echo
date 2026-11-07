package com.msp1974.vacompanion.odysseus.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerHelpersTest {
    @Test fun formatsTimes() {
        assertEquals("0:00", formatPlayerTime(-3.0))
        assertEquals("0:07", formatPlayerTime(7.9))
        assertEquals("3:05", formatPlayerTime(185.0))
        assertEquals("1:02:03", formatPlayerTime(3723.0))
    }

    @Test fun volumeSteps() {
        assertEquals(0.55f, VolumeSteps.next(0.5f, up = true), 1e-6f)
        assertEquals(0.45f, VolumeSteps.next(0.5f, up = false), 1e-6f)
        assertEquals(0.55f, VolumeSteps.next(0.52f, up = true), 1e-6f)   // snaps to the step
        assertEquals(1f, VolumeSteps.next(0.99f, up = true), 1e-6f)
        assertEquals(0f, VolumeSteps.next(0.01f, up = false), 1e-6f)
    }

    @Test fun quickTapsAddUp() {
        // Three quick taps up from 0.5 before the player reports anything back.
        var pending: Float? = null
        var reported = 0.5f
        repeat(3) { i ->
            val now = 1_000L + i * 200
            val from = VolumeSteps.current(reported, pending, sentAtMs = 1_000L + (i - 1) * 200, nowMs = now)
            pending = VolumeSteps.next(from, up = true)
        }
        assertEquals(0.65f, pending!!, 1e-6f)
        // An echo of the first tap doesn't pull it back; the last one's echo ends the override.
        reported = 0.55f
        assertEquals(0.65f, VolumeSteps.current(reported, pending, 1_400L, 1_600L), 1e-6f)
        assertEquals(0.65f, VolumeSteps.current(0.6500001f, pending, 1_400L, 1_600L), 1e-6f)
        assertEquals(0.3f, VolumeSteps.current(0.3f, null, 0L, 1_600L), 1e-6f)
        // Without an echo it gives up after PENDING_MS.
        assertEquals(0.55f, VolumeSteps.current(reported, pending, 1_400L, 1_400L + VolumeSteps.PENDING_MS), 1e-6f)
    }
}
