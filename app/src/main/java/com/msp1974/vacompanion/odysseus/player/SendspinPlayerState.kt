package com.msp1974.vacompanion.odysseus.player

// Odysseus fork: the Sendspin now-playing state as the neutral PlayerState the shared player
// pieces draw, and a player control back as the Sendspin command string the clock always sent.

import com.msp1974.vacompanion.odysseus.music.Control
import com.msp1974.vacompanion.odysseus.music.PlayerState
import com.msp1974.vacompanion.odysseus.music.Repeat
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying

/**
 * This Sendspin state as a [PlayerState]. `playing` is "active and playing" (a stopped stream
 * shows the play button, as the clock always did); `positionAtMs` moves from Sendspin's clock
 * ([android.os.SystemClock.elapsedRealtime]) to the wall clock every PlayerState is on
 * ([PlayerClock], [wallMinusElapsedMs]); pushed artwork goes to `artwork`; Sendspin's playback `speed` becomes `rate`. Volume and mute
 * aren't sent over this path, so they're never in `can`.
 */
fun SendspinNowPlaying.toPlayerState(wallMinusElapsedMs: Long = PlayerClock.wallMinusElapsed()): PlayerState = PlayerState(
    title = title.takeIf { it.isNotBlank() },
    artist = artist.takeIf { it.isNotBlank() },
    album = album.takeIf { it.isNotBlank() },
    artUrl = artworkUrl,
    artwork = artwork,
    playing = active && playing,
    durationS = if (durationMs > 0) durationMs / 1000.0 else null,
    positionS = positionMs.coerceAtLeast(0) / 1000.0,
    positionAtMs = PlayerClock.elapsedToWall(positionAtMs, wallMinusElapsedMs),
    shuffle = shuffle,
    repeat = when (repeat) { "one" -> Repeat.ONE; "all" -> Repeat.ALL; else -> Repeat.OFF },
    volume = null,
    muted = false,
    can = SendspinControls.can(commands),
    rate = if (speed > 0) speed / 1000.0 else 1.0,
)

/** The Sendspin command for [control] in this state, or null when the server doesn't take it. */
fun SendspinNowPlaying.commandFor(control: Control, value: Any? = null): String? =
    SendspinControls.command(control, value, commands, shuffle, repeat)

/** Plain rules between Sendspin's command strings and [Control]. */
object SendspinControls {
    private val REPEAT_CYCLE = listOf("off", "all", "one")

    fun can(commands: Set<String>): Set<Control> = buildSet {
        if ("play" in commands) add(Control.PLAY)
        if ("pause" in commands) add(Control.PAUSE)
        if ("next" in commands) add(Control.NEXT)
        if ("previous" in commands) add(Control.PREVIOUS)
        if ("shuffle" in commands || "unshuffle" in commands) add(Control.SHUFFLE)
        if (commands.any { it.startsWith("repeat_") }) add(Control.REPEAT)
    }

    /**
     * SHUFFLE takes an optional Boolean (else it toggles), REPEAT an optional [Repeat] (else the
     * next of off, all, one the server offers, as the upstream overlay does).
     */
    fun command(control: Control, value: Any?, commands: Set<String>, shuffle: Boolean, repeat: String): String? {
        val command = when (control) {
            Control.PLAY -> "play"
            Control.PAUSE -> "pause"
            Control.NEXT -> "next"
            Control.PREVIOUS -> "previous"
            Control.SHUFFLE -> if ((value as? Boolean) ?: !shuffle) "shuffle" else "unshuffle"
            Control.REPEAT -> when (value) {
                is Repeat -> "repeat_" + value.name.lowercase()
                else -> {
                    val start = REPEAT_CYCLE.indexOf(repeat).coerceAtLeast(0)
                    (1..REPEAT_CYCLE.size).map { "repeat_" + REPEAT_CYCLE[(start + it) % REPEAT_CYCLE.size] }
                        .firstOrNull { it in commands }
                }
            }
            Control.SEEK, Control.VOLUME, Control.MUTE, Control.GROUP -> null
        }
        return command?.takeIf { it in commands }
    }
}
