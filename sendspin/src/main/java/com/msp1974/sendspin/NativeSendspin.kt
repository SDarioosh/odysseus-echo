package com.msp1974.sendspin

import java.nio.ByteBuffer

/**
 * Events from the native Sendspin player. [onAudioWrite] fires on the native sync thread and is
 * the hot path; the rest fire on the native loop thread. None run on the Android main thread.
 */
interface NativeSendspinCallbacks {
    /**
     * Write decoded PCM to the audio output, blocking at most [timeoutMs]. Returns the number of
     * bytes consumed, which must be a whole number of PCM frames: the native side counts played
     * frames from it.
     */
    fun onAudioWrite(buffer: ByteBuffer, length: Int, timeoutMs: Int): Int

    fun onStreamStart(sampleRate: Int, channels: Int, bitDepth: Int)

    fun onStreamEnd()

    /** The server changed the player volume (0-100). */
    fun onVolumeChanged(volume: Int)

    fun onMuteChanged(muted: Boolean)

    /** [serverName] is non-null only on the connected edge. */
    fun onConnectionChanged(connected: Boolean, serverName: String?)

    /**
     * The track changed or its progress was updated. This is the full current state: absent
     * strings are null and absent numbers -1. [progressMs] was the position when this arrived;
     * [speed] is the playback speed in thousandths (1000 = normal, 0 = paused).
     */
    fun onMetadata(
        title: String?,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        progressMs: Long,
        durationMs: Long,
        speed: Int,
    )

    /** The connection to the server was lost, so the track details are no longer valid. */
    fun onMetadataCleared()

    /** The group's playback state: true while it is playing. */
    fun onPlaybackStateChanged(playing: Boolean)

    /** The id of the group this player belongs to, as reported by the server. */
    fun onGroupChanged(groupId: String)

    /**
     * The server's playback controls: [commands] is the comma-separated names of the commands it
     * currently accepts (e.g. "play,pause,next,shuffle,repeat_all"), [shuffle] whether shuffle is
     * on, and [repeat] the repeat mode - "off", "one" or "all".
     */
    fun onControllerState(commands: String, shuffle: Boolean, repeat: String)

    /**
     * The colour palette the server derived from the current track, as opaque ARGB ints; 0 means
     * the server didn't provide that colour. The background colours are safe behind the matching
     * foreground: [onDark] on [backgroundDark], [onLight] on [backgroundLight].
     */
    fun onColors(backgroundDark: Int, backgroundLight: Int, onDark: Int, onLight: Int)

    /**
     * Odysseus fork (artwork role): album art pushed by the server as an encoded image (JPEG, up
     * to 320 px square). Called on the library's decode thread as soon as it arrives; decode it
     * there, but show it only at [onArtworkDisplay].
     */
    fun onArtworkImage(data: ByteArray)

    /** Show the image from the last [onArtworkImage]: its track has started. */
    fun onArtworkDisplay()

    /** The server cleared the artwork (track without art), or the stream ended. */
    fun onArtworkClear()

    // --- Odysseus fork: encryption and pairing (sendspin-cpp v0.9) ---------------------------

    /**
     * The connected server's handshake used a pairing record ([paired]) or not (an unpaired
     * server). Fires on admission and again after the rekey that follows a successful pairing.
     */
    fun onTrustChanged(paired: Boolean)

    /** Show [code] (six digits) for the server's pairing attempt; null takes it off screen. */
    fun onPairingCode(code: String?)

    /**
     * [open]: a pairing attempt waits for the operator to allow it on the device (after too many
     * wrong codes); answer with [NativeSendspin.nativeConfirmPairingWindow]. False withdraws it.
     */
    fun onPairingWindow(open: Boolean)

    /**
     * A pairing with server [serverId]: [stage] 0 started, 1 succeeded (the record is stored), 2
     * failed with [reason] ("timeout", "code_mismatch", "cancelled", ...).
     */
    fun onPairingResult(stage: Int, serverId: String, reason: String?)
}

/**
 * JNI surface of the Sendspin player. Protocol, time sync, decoding and playback
 * scheduling live in sendspin-cpp; the host provides the audio output and reports playback
 * progress back through [nativeNotifyAudioPlayed].
 */
object NativeSendspin {
    // Codec ids in the flattened formats array.
    const val CODEC_FLAC = 0
    const val CODEC_OPUS = 1
    const val CODEC_PCM = 2

    init {
        System.loadLibrary("sendspin_jni")
    }

    /**
     * [formats] is a flattened list of supported formats, four ints each: codec id, channels,
     * sample rate, bit depth - in the order they are offered to the server. Returns 0 on failure.
     *
     * Odysseus fork: [storageDir] is a private directory for the device key and pairing records
     * (created if missing; the client id is derived from the key, so it must persist).
     * [unpairedAccess]: whether servers that haven't paired may play.
     */
    external fun nativeCreate(
        callbacks: NativeSendspinCallbacks,
        storageDir: String,
        name: String,
        productName: String,
        manufacturer: String,
        softwareVersion: String,
        serverPort: Int,
        audioBufferCapacity: Int,
        formats: IntArray,
        unpairedAccess: Boolean,
    ): Long

    /** Starts the client and its WebSocket server. False if it could not start. */
    external fun nativeStart(handle: Long): Boolean

    /**
     * Stops and frees the client. [restarting] = the player will be back (app/service stopping):
     * goodbye says `restart`, so a server dialling a manual address keeps retrying. False = the
     * owner turned the player off: the library's `shutdown` goodbye.
     */
    external fun nativeDestroy(handle: Long, restarting: Boolean)

    /** Thread-safe. [timestampUs] is on the [nativeMonotonicTimeUs] clock. */
    external fun nativeNotifyAudioPlayed(handle: Long, frames: Int, timestampUs: Long)

    external fun nativeUpdateVolume(handle: Long, volume: Int)

    external fun nativeUpdateMuted(handle: Long, muted: Boolean)

    /**
     * Sends a playback command to the server - "play", "pause", "stop", "next", "previous",
     * "seek", "shuffle", "unshuffle", "repeat_off", "repeat_one" or "repeat_all". [arg] is the
     * target position in ms for "seek". False if the command is unknown.
     */
    external fun nativeSendCommand(handle: Long, command: String, arg: Long): Boolean

    /**
     * Restricts which server may connect to the one at [ip], a dotted IPv4 address. Blank accepts
     * any server (Auto). A connected server at another address is disconnected, so this can be
     * changed while connected.
     */
    external fun nativeSetPreferredServer(handle: Long, ip: String)

    /**
     * Odysseus fork: ids of servers allowed to connect from any address, even when a preferred
     * server is set, separated by commas or whitespace. Blank trusts none.
     */
    external fun nativeSetTrustedServerIds(handle: Long, ids: String)

    /** Odysseus fork: whether servers that haven't paired may play. Any thread, any time. */
    external fun nativeSetUnpairedAccess(handle: Long, enabled: Boolean)

    /** Odysseus fork: the operator allows pairing (see [NativeSendspinCallbacks.onPairingWindow]). */
    external fun nativeConfirmPairingWindow(handle: Long)

    external fun nativeCancelPairingWindow(handle: Long)

    /** Odysseus fork: the id servers know this player by (derived from its key). After start. */
    external fun nativeClientId(handle: Long): String

    external fun nativeIsConnected(handle: Long): Boolean

    external fun nativeMonotonicTimeUs(): Long

    /** 0 none .. 5 verbose. */
    external fun nativeSetLogLevel(level: Int)
}
