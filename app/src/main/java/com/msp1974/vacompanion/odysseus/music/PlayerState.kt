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
) {
    fun positionNow(nowMs: Long): Double? {
        val position = positionS ?: return null
        val now = if (playing) position + (nowMs - positionAtMs) / 1000.0 else position
        return durationS?.let { now.coerceAtMost(it) } ?: now
    }
}

enum class Control { PLAY, PAUSE, NEXT, PREVIOUS, SEEK, SHUFFLE, REPEAT, VOLUME, MUTE, GROUP }
