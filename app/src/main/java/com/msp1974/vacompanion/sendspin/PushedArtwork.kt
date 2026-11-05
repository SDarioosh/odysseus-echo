package com.msp1974.vacompanion.sendspin

// Odysseus fork: which album art pushed by the Sendspin server (artwork role) is on screen.
// Plain Kotlin, generic over the image type so it is unit-tested without Android
// (PushedArtworkTest). Not thread-safe: SendspinPlayer calls it under its now-playing lock.
//
// The library hands over an image when it arrives (onImage) and says when to show it - at the
// track change it belongs to (onDisplay). It clears the slot in two cases that look alike:
//  - while streaming, the server's clear for the slot: the current track has no art. Drop it.
//  - at stream end (pause, stop): every slot is cleared. Keep the image while the paused track
//    lingers on screen; drop it once another track comes up without new art.

class PushedArtwork<T : Any> {
    private var pending: T? = null
    private var stale = false
    private var trackAtClear: String? = null

    /** The image to show, or null for none (fall back to the artwork URL). */
    var shown: T? = null
        private set

    fun onImage(image: T) {
        pending = image
    }

    /** Returns true when [shown] changed. */
    fun onDisplay(): Boolean {
        val image = pending ?: return false
        pending = null
        shown = image
        stale = false
        trackAtClear = null
        return true
    }

    /** [streaming]: audio is still streaming; [track]: the current track's key. Returns true when [shown] changed. */
    fun onClear(streaming: Boolean, track: String): Boolean {
        pending = null
        if (shown == null) return false
        if (streaming) {
            shown = null
            stale = false
            return true
        }
        stale = true
        trackAtClear = track
        return false
    }

    /** The current track changed to [track]. Returns true when [shown] changed. */
    fun onTrack(track: String): Boolean {
        if (!stale || shown == null || track == trackAtClear) return false
        shown = null
        stale = false
        trackAtClear = null
        return true
    }

    fun reset() {
        pending = null
        shown = null
        stale = false
        trackAtClear = null
    }
}
