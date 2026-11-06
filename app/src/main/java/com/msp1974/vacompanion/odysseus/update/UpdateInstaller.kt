package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: hands a downloaded, verified APK to Android's PackageInstaller in a session.
// The app is the home app and a device admin, not the device owner, so Android always asks the
// person to confirm (STATUS_PENDING_USER_ACTION, opened by UpdateInstallReceiver). An update
// only installs over a build signed with the same key (docs/odysseus/build.md).

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import timber.log.Timber
import java.io.File

object UpdateInstaller {

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings → Install unknown apps, for this app. */
    fun openInstallPermissionScreen(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w("Update: no Install unknown apps screen: %s", e.message)
            UpdateManager.onInstallStatus(
                "This device has no Install unknown apps screen. Over adb: " +
                    "adb shell appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow",
            )
        }
    }

    /**
     * Checks [file] against [release] and the installed app, then commits an install session.
     * Returns why it was refused, or null when Android now shows its prompt. Runs off the main thread.
     */
    fun install(context: Context, file: File, release: AppRelease, installedCode: Long): String? {
        if (!UpdateClient.matches(file, release)) {
            file.delete()
            return "the downloaded file changed; it was deleted and will be downloaded again"
        }
        val pm = context.packageManager
        val archive = archiveInfo(pm, file)
        val problem = UpdatePolicy.apkProblem(
            expectedPackage = context.packageName,
            installedCode = installedCode,
            release = release,
            archivePackage = archive?.packageName,
            archiveCode = archive?.let { versionCode(it) },
            signaturesMatch = archive?.let { signaturesMatch(pm, context.packageName, it) },
        )
        if (problem != null) return problem

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("odysseus-echo.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags,
                )
                Timber.i("Update: install session %d for %s committed", sessionId, release.versionName)
                session.commit(pending.intentSender)
            }
            return null
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            return e.message ?: e.javaClass.simpleName
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        return pm.getPackageArchiveInfo(file.path, flags)
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    /** Same signing certificates as the installed app; null when Android can't tell (before 9). */
    private fun signaturesMatch(pm: PackageManager, packageName: String, archive: PackageInfo): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val installed = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            val offered = archive.signingInfo ?: return null
            if (installed == null) return null
            val a = installed.apkContentsSigners.map { it.toCharsString() }.toSet()
            val b = offered.apkContentsSigners.map { it.toCharsString() }.toSet()
            a == b
        } catch (e: Exception) {
            Timber.w("Update: couldn't compare signatures: %s", e.message)
            null
        }
    }
}

/** Android's answers to an install session (manifest receiver, not exported). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Timber.i("Update: installer status %d %s", status, message ?: "")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            try {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                UpdateManager.onInstallStatus("Android didn't show the install prompt: ${e.message}")
            }
            return
        }
        UpdateManager.onInstallStatus(UpdatePolicy.installStatusMessage(status, message))
    }
}
