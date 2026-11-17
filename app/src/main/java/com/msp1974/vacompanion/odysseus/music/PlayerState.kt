package com.msp1974.vacompanion.odysseus.music

import android.graphics.Bitmap

enum class Repeat { OFF, ONE, ALL }

data class PlayerState(                                   // neutral; I3's UI takes this
    val title: String?, val artist: String?, val album: String?,
    val artUrl: String?,                                   // a HA path or the hub's C8 URL
    val artwork: Bitmap? = null,                           // pushed (Sendspin)
    val playing: Boolean, val durationS: Double?, val positionS: Double?,
    val positionAtMs: Long, val shuffle: Boolean, val repeat: Repeat,
    val volume: Float?, val muted: Boolean, val can: Set<Control>,
    val rate: Double = 1.0,                                // playback speed (Sendspin's speed / 1000)
) {
    /** The position at [nowMs], advancing at [rate] while playing; within 0 and the duration. */
    fun positionNow(nowMs: Long): Double? {
        val position = positionS ?: return null
        val now = if (playing) position + (nowMs - positionAtMs) / 1000.0 * rate else position
        val clamped = now.coerceAtLeast(0.0)
        return durationS?.let { clamped.coerceAtMost(it) } ?: clamped
    }
}

enum class Control { PLAY, PAUSE, NEXT, PREVIOUS, SEEK, SHUFFLE, REPEAT, VOLUME, MUTE, GROUP }
