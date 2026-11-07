package com.msp1974.vacompanion.odysseus.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerStateTest {
    private fun state(playing: Boolean = true, duration: Double? = 200.0, position: Double? = 10.0) = PlayerState(
        title = "Fictional Song", artist = "Nobody", album = null, artUrl = null,
        playing = playing, durationS = duration, positionS = position, positionAtMs = 1_000,
        shuffle = false, repeat = Repeat.OFF, volume = null, muted = false, can = emptySet(),
    )

    @Test fun advancesWhilePlaying() = assertEquals(15.5, state().positionNow(6_500)!!, 1e-9)

    @Test fun holdsWhilePaused() = assertEquals(10.0, state(playing = false).positionNow(60_000)!!, 1e-9)

    @Test fun clampsToDuration() = assertEquals(200.0, state().positionNow(1_000_000)!!, 1e-9)

    @Test fun noDurationNoClamp() = assertEquals(1009.0, state(duration = null).positionNow(1_000_000)!!, 1e-9)

    @Test fun nullWithoutPosition() = assertNull(state(position = null).positionNow(5_000))
}
