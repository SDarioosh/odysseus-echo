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
}
