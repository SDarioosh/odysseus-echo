package com.msp1974.vacompanion.odysseus.lock

// Odysseus fork: what the satellite refuses from Home Assistant while Lock mode is locked
// (docs/odysseus/lock-mode.md). Pure Kotlin, unit-tested (SatelliteLockRulesTest); SatelliteLock.kt
// runs it for Satellite.kt.

import com.msp1974.vacompanion.wyoming.WyomingEvent

object SatelliteLockRules {
    /** Home Assistant actions that would wake, play or show something. Logs, refresh, pause, stop and interrupt still work. */
    val IGNORED_ACTIONS = setOf(
        "play-media", "play", "set-volume", "toast-message", "wake", "alarm", "navigate",
    )

    fun ignoresAction(locked: Boolean, action: String): Boolean = locked && action in IGNORED_ACTIONS

    /** What to do with a Wyoming packet from Home Assistant (not a custom event). */
    enum class Packet {
        /** Not ours: route it as upstream does. */
        PASS,

        /** An announcement's audio-start while locked: drop it and what follows. */
        START_DROPPING,

        /** An audio-chunk of a dropped announcement. */
        DROP,

        /** The dropped announcement's audio-stop: drop it and answer `played`, so HA isn't left waiting. */
        STOP_DROPPING,

        /** A conversation HA starts (`transcribe`) while locked: answer `audio-stop` at once, send no audio. */
        REFUSE,
    }

    /**
     * [locked]: Lock mode now; [dropping]: an announcement's audio is being dropped; [pipelineActive]:
     * the satellite has a pipeline that hasn't ended (it gets its own packets, locked or not; asked
     * only when needed). [STOP_DROPPING] ends the dropping, [START_DROPPING] starts it.
     */
    fun serverPacket(type: String, locked: Boolean, dropping: Boolean, pipelineActive: () -> Boolean): Packet {
        if (!locked && !dropping) return Packet.PASS
        if (pipelineActive()) return Packet.PASS
        return when (type) {
            WyomingEvent.AUDIO_START -> if (locked) Packet.START_DROPPING else Packet.PASS
            WyomingEvent.AUDIO_CHUNK -> if (dropping) Packet.DROP else Packet.PASS
            WyomingEvent.AUDIO_STOP -> if (dropping) Packet.STOP_DROPPING else Packet.PASS
            WyomingEvent.TRANSCRIBE -> if (locked) Packet.REFUSE else Packet.PASS
            else -> Packet.PASS
        }
    }
}
