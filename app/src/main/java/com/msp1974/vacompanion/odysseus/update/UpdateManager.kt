package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: updates from the owner's Odysseus hub (docs/odysseus/updates.md). Upstream's
// GitHub updates stay off (utils/Updater.kt); this is a separate path:
//
//   1. check  GET <voice base>/app/latest a minute after the app starts, then daily (hourly
//             while a check failed or an update waits), and on "Check now";
//   2. fetch  a newer version_code (than BuildConfig.VERSION_CODE) is downloaded over Wi-Fi into
//             files/odysseus_update/, resumed if cut off, checked for size and sha256;
//   3. offer  "Update ready" in Menu → Odysseus → Updates and as a banner on the clock;
//   4. install a tap hands the APK to Android's PackageInstaller, which asks for confirmation.
//
// The one process-wide holder, as Compose state; the decisions are in UpdatePolicy (tested).

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.msp1974.vacompanion.BuildConfig
import com.msp1974.vacompanion.odysseus.OdysseusLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File

sealed interface UpdateState {
    /** Not checked yet since the app started. */
    data object Idle : UpdateState
    /** No Odysseus link (Menu → Odysseus), so nothing to ask. */
    data object NotLinked : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** The hub has no release uploaded. */
    data object NoRelease : UpdateState
    /** A newer version the device hasn't downloaded yet, and why ([note]). */
    data class Waiting(val release: AppRelease, val note: String) : UpdateState
    data class Downloading(val release: AppRelease, val done: Long) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

object UpdateManager {
    private const val START_DELAY_MS = 60_000L
    private const val TICK_MS = 15 * 60_000L
    private const val DIR = "odysseus_update"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var appContext: Context? = null
    private var store: UpdateStore? = null

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** When the hub was last asked (epoch ms, 0 = never), for the settings. */
    var lastCheckMs by mutableLongStateOf(0L)
        private set

    /** The version whose clock banner was dismissed. */
    var dismissedCode by mutableLongStateOf(0L)
        private set

    /** The last install problem, shown under the Install button; null = none. */
    var installMessage by mutableStateOf<String?>(null)
        private set

    val installedCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    /** Starts the daily check (VACAApplication). Safe to call more than once. */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val s = UpdateStore(app)
        store = s
        lastCheckMs = s.lastCheckMs
        dismissedCode = s.dismissedCode
        if (s.installingCode > 0) {
            Timber.i(
                "Update: asked Android to install %d; running %d (%s)",
                s.installingCode, installedCode, if (installedCode >= s.installingCode) "updated" else "not installed",
            )
            s.installingCode = 0
        }
        scope.launch {
            delay(START_DELAY_MS)
            check(manual = false)
            while (true) {
                delay(TICK_MS)
                if (UpdatePolicy.checkDue(System.currentTimeMillis(), s.lastCheckMs, s.lastCheckSettled)) {
                    check(manual = false)
                }
            }
        }
    }

    /** "Check now" in the settings: ignores an earlier failed download of the same version. */
    fun checkNow(context: Context) {
        start(context)
        scope.launch { check(manual = true) }
    }

    fun dismissBanner() {
        val ready = state as? UpdateState.Ready ?: return
        dismissedCode = ready.release.versionCode
        store?.dismissedCode = dismissedCode
    }

    /** The clock banner: a downloaded update not dismissed for this version, else null. */
    val bannerRelease: AppRelease?
        get() = (state as? UpdateState.Ready)?.release?.takeIf { it.versionCode != dismissedCode }

    private suspend fun check(manual: Boolean) = lock.withLock {
        val context = appContext ?: return@withLock
        val s = store ?: return@withLock
        OdysseusLink.init(context)
        if (!OdysseusLink.isConfigured) {
            state = UpdateState.NotLinked
            return@withLock
        }
        val keepReady = state as? UpdateState.Ready
        if (keepReady == null) state = UpdateState.Checking
        val now = System.currentTimeMillis()
        val client = UpdateClient(LinkUpdateHttp)
        val dir = File(context.filesDir, DIR)
        var settled = true
        when (val r = client.latest()) {
            is CheckResult.Failed -> {
                Timber.w("Update: check failed: %s", r.message)
                state = keepReady ?: UpdateState.Failed("Couldn't check: ${r.message}")
                settled = false
            }
            CheckResult.NoRelease -> {
                Timber.i("Update: the hub has no release")
                dir.deleteRecursively()
                state = UpdateState.NoRelease
            }
            is CheckResult.Found -> {
                val release = r.release
                if (!UpdatePolicy.isNewer(release, installedCode)) {
                    Timber.i("Update: up to date (%d, hub %d)", installedCode, release.versionCode)
                    dir.deleteRecursively()
                    state = UpdateState.UpToDate
                } else if (keepReady != null && keepReady.release == release && keepReady.file.exists()) {
                    state = keepReady
                } else {
                    settled = fetch(client, release, dir, manual, s, now)
                }
            }
        }
        s.lastCheckMs = now
        s.lastCheckSettled = settled
        lastCheckMs = now
    }

    /** Downloads [release] if allowed; true when nothing is left to retry. */
    private fun fetch(client: UpdateClient, release: AppRelease, dir: File, manual: Boolean, s: UpdateStore, now: Long): Boolean {
        val gate = UpdatePolicy.downloadGate(release, onWifi(), manual, s.lastFailure, now)
        Timber.i("Update: %s (%d) offered; download %s", release.versionName, release.versionCode, gate)
        when (gate) {
            DownloadGate.NOT_WIFI -> {
                state = UpdateState.Waiting(release, "Downloads only over Wi-Fi")
                return false
            }
            DownloadGate.FAILED_RECENTLY -> {
                state = UpdateState.Waiting(release, "The last download failed; tries again within a day")
                return false
            }
            DownloadGate.GO -> Unit
        }
        state = UpdateState.Downloading(release, 0L)
        var lastShown = 0L
        val result = client.download(release, dir) { done, _ ->
            // Compose state at most every 512 KB.
            if (done - lastShown >= 512 * 1024 || done == release.size) {
                lastShown = done
                state = UpdateState.Downloading(release, done)
            }
        }
        return when (result) {
            is DownloadResult.Ok -> {
                Timber.i("Update: %s downloaded and verified", release.versionName)
                s.lastFailure = null
                installMessage = null
                state = UpdateState.Ready(release, result.file)
                true
            }
            is DownloadResult.Failed -> {
                Timber.w("Update: download of %s failed: %s", release.versionName, result.message)
                s.lastFailure = DownloadFailure(release.versionCode, now)
                state = UpdateState.Waiting(release, "Download failed: ${result.message}")
                false
            }
        }
    }

    private fun onWifi(): Boolean {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Install (the settings button and the clock banner): Android shows its own prompt. Needs
     * "Install unknown apps" for this app; without it, opens that screen instead.
     */
    fun install(context: Context) {
        val ready = state as? UpdateState.Ready ?: return
        if (!UpdateInstaller.canInstall(context)) {
            installMessage = "Allow \"Install unknown apps\" for this app, then press Install again."
            UpdateInstaller.openInstallPermissionScreen(context)
            return
        }
        installMessage = "Starting the install…"
        scope.launch {
            val problem = UpdateInstaller.install(context.applicationContext, ready.file, ready.release, installedCode)
            if (problem != null) {
                Timber.w("Update: install of %s refused: %s", ready.release.versionName, problem)
                installMessage = "Can't install: $problem"
            } else {
                store?.installingCode = ready.release.versionCode
                installMessage = "Confirm the install on the screen."
            }
        }
    }

    /** Android's answer from UpdateInstallReceiver; null clears the message. */
    fun onInstallStatus(message: String?) {
        installMessage = message
    }
}
