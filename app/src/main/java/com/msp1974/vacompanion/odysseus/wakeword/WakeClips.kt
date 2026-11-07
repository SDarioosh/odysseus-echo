package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork: wake word training clips (docs/odysseus/wake-word-training.md). Real recordings
// of the owner saying the wake word, sent to the Odysseus hub so the model can be retrained on
// them. Audio goes only to the linked hub, never anywhere else, and is never logged.
//
// Two ways in:
//   * Guided: Menu → Odysseus → Wake word training → Record clips. [startTraining] runs a session
//     of takes; the device doesn't wake while it runs ([isTraining]).
//   * Missed wakes, when "Catch missed wake words" is on: a sound the model nearly took for the
//     wake word, followed within 8 s by a real wake (WakeClipCatcher).
//
// Hooks: the microWakeWord engine calls [onFrame] with each frame and its score
// (wakeword/microwakeword/MicroWakeWordEngine.kt); the wake word handler calls [onWake] on a wake
// and skips waking while [isTraining] (satellite/SatelliteWakeWorkHandler.kt).

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.odysseus.OdysseusLink
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

/** One take of a training session, as the screen shows it. */
data class TakeOutcome(val prompt: String, val score: Float, val heard: Boolean, val silent: Boolean)

data class TrainingState(
    val running: Boolean = false,
    /** 1-based number of the take being recorded (0 between sessions). */
    val take: Int = 0,
    val total: Int = 0,
    val prompt: String = "",
    /** True while the current take is recording ("say it now"). */
    val recording: Boolean = false,
    val outcomes: List<TakeOutcome> = emptyList(),
    /** Why the session stopped early, if it did. */
    val problem: String? = null,
)

data class ClipsStatus(val waiting: Int = 0, val sentThisRun: Int = 0, val sentTotal: Int = 0, val problem: String? = null)

object WakeClips {
    private const val PREFS = "odysseus_wakeword"
    private const val KEY_CATCH = "catch_misses"
    private const val KEY_SENT = "sent_total"

    /** One take: long enough to say the wake word once after the prompt shows. */
    const val TAKE_MS = 3_000L
    private const val BETWEEN_TAKES_MS = 1_200L
    /** No frame for this long during a take: the wake word engine isn't listening. */
    private const val NO_AUDIO_MS = 2_500L

    /** The takes of one session, in order: the ways people really say it. */
    val PROMPTS = listOf(
        "In your normal voice",
        "Quickly, as if in a hurry",
        "Quietly",
        "A bit louder, from further away",
        "Looking away from the device",
        "In your normal voice",
        "Slowly",
        "As you would first thing in the morning",
        "Quietly",
        "From across the room",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catcher = WakeClipCatcher()

    @Volatile private var appContext: Context? = null
    @Volatile private var config: APPConfig? = null
    @Volatile private var queue: WakeClipQueue? = null

    private val _training = MutableStateFlow(TrainingState())
    val training: StateFlow<TrainingState> = _training.asStateFlow()
    private val _status = MutableStateFlow(ClipsStatus())
    val status: StateFlow<ClipsStatus> = _status.asStateFlow()

    /** The device doesn't wake while a training session runs. */
    val isTraining: Boolean get() = _training.value.running

    @Volatile private var lastFrameAt = 0L
    @Volatile private var takeHeard = false
    private var takeResult: CompletableDeferred<WakeClipCatcher.TakeResult>? = null
    private var session: Job? = null
    private val uploadWake = Channel<Unit>(Channel.CONFLATED)
    private var uploader: Job? = null

    /** Prefs and the queue; safe to call often (the settings screen calls it). */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        OdysseusLink.init(app)
        queue = WakeClipQueue(File(app.filesDir, "odysseus/wakeword_clips"))
        catchCached = prefs()?.getBoolean(KEY_CATCH, false) ?: false
        refreshStatus()
        uploader = scope.launch { uploadLoop() }
    }

    /** Also the device's config (its id, the threshold); from the wake word engine. */
    fun install(context: Context, deviceManager: DeviceManager) {
        if (config == null) config = deviceManager.config
        init(context)
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Keep a near miss followed by a wake (and that wake) as training clips. Off by default. */
    var catchMisses: Boolean
        get() = catchCached
        set(value) {
            catchCached = value
            prefs()?.edit()?.putBoolean(KEY_CATCH, value)?.apply()
            if (!value) synchronized(catcher) { catcher.clear() }
        }
    @Volatile private var catchCached = false

    // ---- hooks ----------------------------------------------------------------------------

    /**
     * One frame the wake word model read, with its highest [score] for the active wake word (0
     * when it gave none). Called on the engine's thread for every frame; returns at once unless a
     * take runs or catching misses is on.
     */
    fun onFrame(context: Context, deviceManager: DeviceManager, frame: ShortArray, score: Float) {
        val now = System.currentTimeMillis()
        lastFrameAt = now
        if (appContext == null || config == null) install(context, deviceManager)
        val catching = catchMisses && !LockMode.isLocked
        val out = synchronized(catcher) {
            if (!catching && !catcher.taking) return
            catcher.onFrame(frame, now, score, deviceManager.config.wakeWordThreshold, catching)
        }
        out.take?.let { takeResult?.complete(it) }
        for (clip in out.clips) keep(clip)
    }

    /**
     * The device woke on the wake word with [score] (not during calibration). During training it
     * marks the take as heard and doesn't wake the device.
     */
    fun onWake(score: Float) {
        if (appContext == null) return
        if (isTraining) { takeHeard = true; return }
        val catching = catchMisses && !LockMode.isLocked
        synchronized(catcher) { catcher.onWake(System.currentTimeMillis(), score, catching) }
    }

    // ---- guided training --------------------------------------------------------------------

    fun startTraining(takes: Int = PROMPTS.size) {
        if (session?.isActive == true) return
        _training.value = TrainingState(running = true, total = takes)
        session = scope.launch {
            try {
                runSession(takes)
            } finally {
                synchronized(catcher) { catcher.cancelTake() }
                _training.update { it.copy(running = false, recording = false, take = 0) }
            }
        }
    }

    fun stopTraining() {
        session?.cancel()
    }

    private suspend fun runSession(takes: Int) {
        for (i in 0 until takes) {
            val prompt = PROMPTS[i % PROMPTS.size]
            _training.update { it.copy(take = i + 1, prompt = prompt, recording = false) }
            delay(BETWEEN_TAKES_MS)
            val done = CompletableDeferred<WakeClipCatcher.TakeResult>()
            takeResult = done
            val startedAt = System.currentTimeMillis()
            takeHeard = false
            synchronized(catcher) { catcher.startTake(startedAt, TAKE_MS, prompt) }
            _training.update { it.copy(recording = true) }
            // Wait for the take; if no audio arrives at all, the wake word engine isn't running.
            var result: WakeClipCatcher.TakeResult? = null
            while (result == null && currentCoroutineContext().isActive) {
                result = withTimeoutOrNull(500) { done.await() }
                if (result == null && System.currentTimeMillis() - maxOf(lastFrameAt, startedAt) > NO_AUDIO_MS) {
                    _training.update {
                        it.copy(problem = "The wake word isn't listening (muted, locked, or Home Assistant not connected). " +
                            "Clips need the Hey Athena wake word running on this device.")
                    }
                    return
                }
            }
            takeResult = null
            val r = result ?: return
            _training.update {
                it.copy(
                    recording = false,
                    outcomes = it.outcomes + TakeOutcome(prompt, r.peakScore, takeHeard, r.silent),
                )
            }
            r.clip?.let { keep(it) }
        }
    }

    /** Clears the last session's results from the screen. */
    fun resetTraining() {
        if (!isTraining) _training.value = TrainingState()
    }

    // ---- keeping and uploading --------------------------------------------------------------

    private fun keep(clip: WakeClip) {
        val q = queue ?: return
        val wakeWord = config?.wakeWord?.takeIf { it.isNotBlank() && it != "none" } ?: return
        scope.launch {
            runCatching { q.add(clip, wakeWord) }
                .onSuccess { Timber.i("Wake word clip kept: ${clip.kind.wire}, score %.2f".format(clip.score)) }
                .onFailure { Timber.w(it, "Wake word clip could not be kept") }
            refreshStatus()
            uploadWake.trySend(Unit)
        }
    }

    /** Try sending now ("Send now"). */
    fun uploadNow() { uploadWake.trySend(Unit) }

    private fun refreshStatus() {
        val waiting = runCatching { queue?.size() ?: 0 }.getOrDefault(0)
        val total = prefs()?.getInt(KEY_SENT, 0) ?: 0
        _status.update { it.copy(waiting = waiting, sentTotal = total) }
    }

    private suspend fun uploadLoop() {
        var failures = 0
        while (true) {
            val pass = runCatching { uploadPass() }.getOrElse {
                Timber.w(it, "Wake word clips: upload failed")
                ClipPass.RETRY
            }
            refreshStatus()
            val (problem, waitMs) = when (pass) {
                ClipPass.DONE -> { failures = 0; null to null }
                ClipPass.RETRY -> { failures++; "Odysseus not reachable; will try again" to minOf(60_000L * failures, 15 * 60_000L) }
                ClipPass.AUTH -> "Odysseus refused the token: link this Echo again" to 60 * 60_000L
                ClipPass.NO_ROUTE -> "Odysseus can't take clips yet (update the hub); clips are kept" to 60 * 60_000L
                ClipPass.NOT_LINKED -> "Not linked to Odysseus (Menu → Odysseus); clips are kept" to 5 * 60_000L
            }
            _status.update { it.copy(problem = problem) }
            if (waitMs == null) uploadWake.receive() else withTimeoutOrNull(waitMs) { uploadWake.receive() }
        }
    }

    private fun uploadPass(): ClipPass {
        val q = queue ?: return ClipPass.DONE
        val cfg = config ?: return if (q.size() == 0) ClipPass.DONE else ClipPass.RETRY
        if (q.size() == 0) return ClipPass.DONE
        if (!OdysseusLink.isConfigured) return ClipPass.NOT_LINKED
        val deviceName = OdysseusLink.deviceName.ifBlank { "VACA ${cfg.uuid}" }
        return q.upload(cfg.uuid, deviceName, { contentType, body ->
            runCatching {
                kotlinx.coroutines.runBlocking {
                    OdysseusLink.hubRequest(WakeClipQueue.PATH, contentType, body, readTimeoutMs = 15_000)
                }.let { ClipReply(it.code, it.body) }
            }.getOrElse { ClipReply(null, null) }
        }) {
            val p = prefs()
            p?.edit()?.putInt(KEY_SENT, (p.getInt(KEY_SENT, 0)) + 1)?.apply()
            _status.update { s -> s.copy(sentThisRun = s.sentThisRun + 1) }
            refreshStatus()
        }
    }
}
