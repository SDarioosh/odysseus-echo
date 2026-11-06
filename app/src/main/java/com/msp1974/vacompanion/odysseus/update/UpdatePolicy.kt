package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: when to check the hub for an update, whether to download it, and whether a
// downloaded APK may be installed. Plain Kotlin, unit-tested (UpdatePolicyTest); UpdateManager
// does the I/O.

import java.util.Locale

/** A download of [versionCode] that failed at [atMs] (network, size or sha256 mismatch). */
data class DownloadFailure(val versionCode: Long, val atMs: Long)

enum class DownloadGate {
    /** Download now. */
    GO,
    /** Only over Wi-Fi (or Ethernet); try again at the next check. */
    NOT_WIFI,
    /** This version failed to download less than a day ago; wait (a manual check doesn't). */
    FAILED_RECENTLY,
}

object UpdatePolicy {
    /** A successful check is repeated after a day. */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60_000L

    /** A check that failed, or found an update it couldn't download yet, is retried after an hour. */
    const val RETRY_INTERVAL_MS = 60 * 60_000L

    /** After a failed download the same version is tried again at most once a day. */
    const val FAILURE_BACKOFF_MS = 24 * 60 * 60_000L

    /**
     * Whether a scheduled check is due. [lastCheckMs] 0 = never checked; [lastCheckSettled] false
     * when the last check failed or left an update waiting (no Wi-Fi, failed download). A clock
     * that went backwards counts as due.
     */
    fun checkDue(nowMs: Long, lastCheckMs: Long, lastCheckSettled: Boolean): Boolean {
        if (lastCheckMs <= 0L || nowMs < lastCheckMs) return true
        val interval = if (lastCheckSettled) CHECK_INTERVAL_MS else RETRY_INTERVAL_MS
        return nowMs - lastCheckMs >= interval
    }

    /** Whether [release] is newer than the installed [installedCode]. */
    fun isNewer(release: AppRelease, installedCode: Long): Boolean = release.versionCode > installedCode

    /**
     * Whether to download [release] now. A manual "Check now" ignores an earlier failure but not
     * the Wi-Fi rule.
     */
    fun downloadGate(
        release: AppRelease,
        onWifi: Boolean,
        manual: Boolean,
        lastFailure: DownloadFailure?,
        nowMs: Long,
    ): DownloadGate {
        if (!onWifi) return DownloadGate.NOT_WIFI
        if (!manual && lastFailure != null && lastFailure.versionCode == release.versionCode &&
            nowMs >= lastFailure.atMs && nowMs - lastFailure.atMs < FAILURE_BACKOFF_MS
        ) return DownloadGate.FAILED_RECENTLY
        return DownloadGate.GO
    }

    /**
     * Where to resume a download of [expectedSize] bytes when [partSize] bytes are already on
     * disk: 0 = start over (nothing kept, or more than expected).
     */
    fun resumeFrom(partSize: Long, expectedSize: Long): Long =
        if (partSize in 1 until expectedSize) partSize else 0L

    /**
     * Checks a downloaded APK against what the hub offered and what is installed, before
     * Android's prompt. Null = fine, else the reason to refuse it. [signaturesMatch] null =
     * unknown (Android didn't say); Android then refuses a different key itself.
     */
    fun apkProblem(
        expectedPackage: String,
        installedCode: Long,
        release: AppRelease,
        archivePackage: String?,
        archiveCode: Long?,
        signaturesMatch: Boolean?,
    ): String? = when {
        archivePackage == null || archiveCode == null -> "the download isn't a readable APK"
        archivePackage != expectedPackage -> "the APK is for $archivePackage, not $expectedPackage"
        archiveCode != release.versionCode ->
            "the APK is version $archiveCode, but the hub said ${release.versionCode}"
        archiveCode <= installedCode -> "the APK (version $archiveCode) isn't newer than this app ($installedCode)"
        signaturesMatch == false ->
            "the APK is signed with a different key than this app (see docs/odysseus/build.md, release builds)"
        else -> null
    }

    /**
     * What to show for an install session's final status (PackageInstaller.STATUS_*; the success
     * case is rarely seen, as Android stops the app to replace it). Null = nothing to show.
     */
    fun installStatusMessage(status: Int, message: String?): String? = when (status) {
        STATUS_SUCCESS -> null
        STATUS_FAILURE_ABORTED -> "Install cancelled."
        STATUS_FAILURE_CONFLICT ->
            "Android refused the update: it is signed with a different key than this app, or conflicts with it " +
                "(see docs/odysseus/build.md, release builds)."
        STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
        STATUS_FAILURE_INCOMPATIBLE -> "The update doesn't run on this device."
        STATUS_FAILURE_INVALID -> "Android says the APK is invalid."
        STATUS_FAILURE_BLOCKED -> "Android blocked the install."
        else -> "Install failed" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    // PackageInstaller.STATUS_* values, so the mapping is tested without Android.
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7

    /** "12.3 MB" for the settings. */
    fun sizeLabel(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return String.format(Locale.ROOT, if (mb >= 10) "%.0f MB" else "%.1f MB", mb)
    }
}
