package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder, one per process (docs/odysseus/recording.md). Installed from the
// foreground service, so it runs whether or not Home Assistant is connected, with the screen off
// and the activity in the background. The on-screen buttons (and E6's hardware button) only call
// press() / hold(); the pill and the settings only read [state].
//
// Recordings and voice notes go to Athena through the owner's hub (contract C4), never anywhere
// else, and only after a person pressed something. While anything records, the red pill shows on
// every screen and the screen is woken and kept on.

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.VoiceActivity
import com.msp1974.vacompanion.odysseus.audio.SharedMicrophone
import com.msp1974.vacompanion.players.SoundEffectsPlayer
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the pill, the buttons and the settings show. */
data class RecorderUiState(
    /** Idle, Recording or Note (with when it started). */
    val recorder: RecorderState = RecorderState.Idle,
    val queue: QueueStatus = QueueStatus(0, 0, 0, 0),
    /** Why uploads are waiting, or null when they aren't. */
    val uploadProblem: String? = null,
    /** The last warning (queue full, …), or null. */
    val warning: String? = null,
)

object RecorderController : EventListener {
    const val START_SOUND_URI = "asset:///odysseus/record_start.wav"
    const val STOP_SOUND_URI = "asset:///odysseus/record_stop.wav"
    /** Sent on config.eventBroadcaster when something starts recording; MainActivity wakes the screen. */
    const val EVENT_STARTED = "odysseusRecordingStarted"

    const val RELINK_MESSAGE = "Relink this Echo with Odysseus to upload recordings"
    private const val MIC_STALL_MS = 5_000L
    private const val AUTH_RECHECK_MS = 60_000L
    private const val IDLE_RECHECK_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val machine = RecorderStateMachine()
    private val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var deviceManager: DeviceManager? = null
    private var config: APPConfig? = null
    private var queue: ChunkQueue? = null
    private var transport: HubRelayTransport? = null
    private var uploader: RecordingUploader? = null
    private var sounds: SoundEffectsPlayer? = null

    private var capture: RecordingCapture? = null
    private var ticker: Job? = null

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val forceUpload = AtomicBoolean(false)

    /** Sets the recorder up; called once from the foreground service. Safe to call again. */
    @Synchronized
    fun install(context: Context, deviceManager: DeviceManager) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        this.deviceManager = deviceManager
        config = deviceManager.config
        OdysseusLink.init(app)
        RecorderSettings.init(app)
        val q = ChunkQueue(File(app.filesDir, "odysseus/recordings"))
        queue = q
        transport = HubRelayTransport()
        uploader = RecordingUploader(q, transport!!)
        sounds = SoundEffectsPlayer(app, deviceManager.config)
        deviceManager.config.eventBroadcaster.addListener(this)

        // Under install's lock, before any press can start a session: run later, it could mark a
        // session that has just started as abandoned, and the uploader would end it early.
        val n = runCatching { q.finishAbandoned() }.getOrDefault(0)
        if (n > 0) Timber.i("Recorder: $n session(s) left from an earlier run will be sent as they are")
        scope.launch(Dispatchers.IO) {
            refreshQueue()
            uploadLoop()
        }
        // The privacy button as the record button (E6). It also decides whether Android's
        // mic-mute flag counts: not while the button records (VoiceActivity.micMuted stays false).
        RecordButton.install(app, deviceManager.config)
        // The hardware switch in Mute mode (or anything else) muting the microphone stops a recording.
        scope.launch {
            VoiceActivity.micMuted.collect { muted -> if (muted) stopAll("microphone muted") }
        }
        Timber.i("Recorder installed")
    }

    // --- Gestures: the API the buttons (and E6's hardware button) call -----------------------

    /** A short press: start a recording, stop it, or stop a voice note. */
    fun press() = gesture { now, refusal -> machine.press(now, refusal) }

    /**
     * A hold of [RecorderStateMachine.HOLD_MS], called while still held: start a voice note (or
     * stop the running one). The release after it must not call anything.
     */
    fun hold() = gesture { now, refusal -> machine.hold(now, refusal) }

    /** Stops whatever records, nothing resumes (recording turned off, microphone muted). */
    fun stopAll(why: String) {
        synchronized(this) {
            val effects = machine.stopAll()
            publishState()
            if (effects.isNotEmpty()) Timber.i("Recorder: stopped ($why)")
            perform(effects)
        }
    }

    /** "Upload now": try at once, also after a refused token (the Echo may have been relinked). */
    fun uploadNow() {
        forceUpload.set(true)
        wake.trySend(Unit)
    }

    val isInstalled: Boolean get() = appContext != null

    /**
     * The clock of the gestures, the limits and [RecorderState]'s start times: time since boot,
     * so a wall-clock change (network time after boot, the owner setting the date) can't stop a
     * recording at once or let a note run past 5 min. The pill counts with it too.
     */
    fun clockMs(): Long = SystemClock.elapsedRealtime()

    private fun gesture(step: (Long, String?) -> List<RecorderEffect>) {
        // One lock for the step and its effects, so two quick gestures can't interleave.
        synchronized(this) {
            val effects = step(clockMs(), refusal())
            publishState()
            perform(effects)
        }
    }

    /** Why nothing may start now, or null. */
    private fun refusal(): String? {
        val context = appContext ?: return "The recorder isn't ready yet"
        val cfg = config ?: return "The recorder isn't ready yet"
        return when {
            !RecorderSettings.enabled -> "Recording is off (Menu → Odysseus → Recording)"
            !OdysseusLink.isConfigured -> "Link this Echo with Odysseus to record"
            cfg.isMuted || VoiceActivity.micMuted.value -> "The microphone is muted: not recording"
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "No microphone permission: not recording"
            else -> null
        }
    }

    // Effects run in order, under the lock, on the calling thread; nothing here waits long.
    private fun perform(effects: List<RecorderEffect>) {
        for (e in effects) {
            // A session that could not start leaves the recorder idle: no start sound after it.
            if (e == RecorderEffect.PlayStartSound && machine.state == RecorderState.Idle) continue
            when (e) {
                is RecorderEffect.StartSession -> try {
                    startSession(e.kind, e.quiet)
                } catch (ex: Exception) {
                    // Storage gone or full: don't show a REC pill for audio that isn't kept.
                    Timber.e(ex, "Recorder: could not start a session")
                    machine.stopAll()
                    publishState()
                    toast("Could not start recording: no storage")
                }
                is RecorderEffect.EndSession -> {
                    capture?.finish()
                    capture = null
                }
                RecorderEffect.PlayStartSound -> playSound(START_SOUND_URI)
                RecorderEffect.PlayStopSound -> playSound(STOP_SOUND_URI)
                is RecorderEffect.Refused -> {
                    Timber.i("Recorder: refused: ${e.reason}")
                    toast(e.reason)
                }
            }
        }
        if (machine.state == RecorderState.Idle) {
            ticker?.cancel(); ticker = null
        } else if (ticker == null) {
            ticker = scope.launch { tick() }
        }
    }

    private fun startSession(kind: RecordingKind, quiet: Boolean) {
        val context = appContext ?: return
        val dm = deviceManager ?: return
        val q = queue ?: return
        val now = System.currentTimeMillis()
        val id = UploadRules.newSessionId()
        val kindName = if (kind == RecordingKind.NOTE) RecordingUploader.KIND_NOTE else RecordingUploader.KIND_RECORDING
        val dir = q.createSession(SessionInfo(id, kindName, UploadRules.deviceField(dm.config.uuid), now))
        capture = object : RecordingCapture(
            kind, id, now, dir, q, SharedMicrophone.raw(context, dm), scope, Dispatchers.IO,
        ) {
            override fun onChunkWritten(sequence: Int) {
                val dropped = q.enforceCap()
                if (dropped > 0) {
                    val msg = "Upload queue full: deleted the $dropped oldest recording(s) not yet sent"
                    Timber.w("Recorder: $msg")
                    _state.value = _state.value.copy(warning = msg)
                    toast(msg)
                }
                refreshQueue()
                wake.trySend(Unit)
            }

            override fun onFinished(chunks: Int) {
                refreshQueue()
                wake.trySend(Unit)
            }
        }
        Timber.i("Recorder: started $kindName $id${if (quiet) " (after a note)" else ""}")
        if (!quiet) config?.eventBroadcaster?.notifyEvent(Event(EVENT_STARTED, false, true))
    }

    /** Once a second while something runs: maximum lengths, and a microphone that went quiet. */
    private suspend fun tick() {
        while (scope.isActive) {
            delay(1_000)
            val now = clockMs()
            synchronized(this) {
                // A note that interrupted a recording resumes it only if recording may start now.
                val effects = machine.tick(now, refusal())
                if (effects.isNotEmpty()) {
                    Timber.i("Recorder: maximum length reached")
                    publishState()
                    perform(effects)
                }
            }
            capture?.let { if (System.currentTimeMillis() - it.lastFrameAtMs > MIC_STALL_MS) it.resubscribe() }
        }
    }

    private fun playSound(uri: String) {
        val player = sounds ?: return
        if (config?.doNotDisturb == true) return
        scope.launch {
            runCatching { player.play(Uri.parse(uri)) }.onFailure { Timber.w(it, "Recorder: sound failed") }
        }
    }

    private fun toast(message: String) {
        config?.eventBroadcaster?.notifyEvent(Event("showToastMessage", "", message))
    }

    private fun publishState() {
        _state.value = _state.value.copy(recorder = machine.state)
    }

    private fun refreshQueue() {
        val q = queue ?: return
        runCatching { q.status() }.onSuccess { _state.value = _state.value.copy(queue = it) }
    }

    // --- Uploads ---------------------------------------------------------------------------

    private suspend fun uploadLoop() {
        val up = uploader ?: return
        val t = transport ?: return
        var failures = 0
        var authBlockedToken: Int? = null
        while (scope.isActive) {
            if (forceUpload.getAndSet(false)) {
                failures = 0
                authBlockedToken = null
            }
            if (authBlockedToken != null && t.tokenFingerprint() != authBlockedToken) {
                Timber.i("Recorder: the Echo was relinked; uploading again")
                authBlockedToken = null
            }
            var waitMs = IDLE_RECHECK_MS
            var backingOff = false
            when {
                authBlockedToken != null -> {
                    waitMs = AUTH_RECHECK_MS
                    backingOff = true
                }
                !OdysseusLink.isConfigured -> {
                    setProblem(if (queueWaiting()) "Not linked with Odysseus: recordings wait on the device" else null)
                    waitMs = AUTH_RECHECK_MS
                }
                else -> when (runCatching { up.runPass { refreshQueue() } }.getOrDefault(PassResult.RETRY)) {
                    PassResult.DONE -> {
                        failures = 0
                        setProblem(null)
                    }
                    PassResult.RETRY -> {
                        failures++
                        waitMs = UploadRules.retryDelayMs(failures)
                        backingOff = true
                        setProblem("Can't reach the hub; trying again in ${waitMs / 60_000} min")
                    }
                    PassResult.AUTH -> {
                        authBlockedToken = t.tokenFingerprint()
                        waitMs = AUTH_RECHECK_MS
                        backingOff = true
                        setProblem(RELINK_MESSAGE)
                        toast(RELINK_MESSAGE)
                    }
                }
            }
            refreshQueue()
            waitForWake(waitMs, backingOff)
        }
    }

    /** Waits [ms], or less when woken: by a new chunk unless backing off, by "Upload now" always. */
    private suspend fun waitForWake(ms: Long, backingOff: Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            withTimeoutOrNull(left) { wake.receive() } ?: return
            if (forceUpload.get() || !backingOff) return
        }
    }

    private fun queueWaiting(): Boolean = (_state.value.queue.chunksWaiting) > 0

    private fun setProblem(problem: String?) {
        if (_state.value.uploadProblem != problem) _state.value = _state.value.copy(uploadProblem = problem)
    }

    // --- Events ----------------------------------------------------------------------------

    override fun onEventTriggered(event: Event) {
        if (event.eventName == "isMuted" && event.newValue == true) {
            scope.launch { stopAll("muted") }
        }
    }

    /** Recording was turned off in the settings: stop what runs. */
    fun onRecordingDisabled() = stopAll("recording turned off")
}
