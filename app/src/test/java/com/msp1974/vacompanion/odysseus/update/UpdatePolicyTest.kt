package com.msp1974.vacompanion.odysseus.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val sha = "a".repeat(64)
    private val release = AppRelease("odysseus-echo", "0.14.0+odysseus.30", 1400030, 1000, sha, "notes")
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    @Test
    fun parsesContractC2AndIgnoresUnknownKeys() {
        val r = AppRelease.parse(
            """{"app": "odysseus-echo", "version_name": "0.14.0+odysseus.30", "version_code": 1400030,
               "size": 31457280, "sha256": "${"9F2C".repeat(16)}", "notes": "Kiosk settings; Today page.",
               "uploaded": "2026-11-06"}""",
        )
        assertEquals(1400030L, r.versionCode)
        assertEquals("0.14.0+odysseus.30", r.versionName)
        assertEquals(31457280L, r.size)
        assertEquals("9f2c".repeat(16), r.sha)
        assertEquals("Kiosk settings; Today page.", r.notes)
        assertNull(r.problem())
    }

    @Test
    fun rejectsReleasesItCannotUse() {
        assertTrue(release.copy(app = "other").problem()!!.contains("other"))
        assertTrue(release.copy(versionCode = 0).problem() != null)
        assertTrue(release.copy(size = 0).problem() != null)
        assertTrue(release.copy(sha256 = "9f2c...").problem() != null)
        assertNull(release.copy(notes = null).problem())
    }

    @Test
    fun comparesVersionCodeNotName() {
        // 0.14.0+odysseus.30 vs .29: semver would call them equal.
        assertTrue(UpdatePolicy.isNewer(release, 1400029))
        assertFalse(UpdatePolicy.isNewer(release, 1400030))
        assertFalse(UpdatePolicy.isNewer(release, 1500000))
    }

    @Test
    fun checksDailyAndRetriesHourlyAfterAFailure() {
        val t = 1_000_000_000_000L
        assertTrue(UpdatePolicy.checkDue(t, 0L, true))
        assertFalse(UpdatePolicy.checkDue(t + day - 1, t, true))
        assertTrue(UpdatePolicy.checkDue(t + day, t, true))
        assertFalse(UpdatePolicy.checkDue(t + hour - 1, t, false))
        assertTrue(UpdatePolicy.checkDue(t + hour, t, false))
        assertTrue("clock went back", UpdatePolicy.checkDue(t - 1, t, true))
    }

    @Test
    fun downloadsOnlyOverWifi() {
        assertEquals(DownloadGate.NOT_WIFI, UpdatePolicy.downloadGate(release, onWifi = false, manual = true, lastFailure = null, nowMs = 0))
        assertEquals(DownloadGate.GO, UpdatePolicy.downloadGate(release, onWifi = true, manual = false, lastFailure = null, nowMs = 0))
    }

    @Test
    fun afterAFailureTheSameVersionWaitsADayUnlessCheckedByHand() {
        val t = 1_000_000_000_000L
        val failed = DownloadFailure(release.versionCode, t)
        assertEquals(DownloadGate.FAILED_RECENTLY, UpdatePolicy.downloadGate(release, true, false, failed, t + day - 1))
        assertEquals(DownloadGate.GO, UpdatePolicy.downloadGate(release, true, false, failed, t + day))
        assertEquals(DownloadGate.GO, UpdatePolicy.downloadGate(release, true, true, failed, t + 1))
        // Another version isn't held back by this one's failure.
        assertEquals(DownloadGate.GO, UpdatePolicy.downloadGate(release.copy(versionCode = 1400031), true, false, failed, t + 1))
        // A clock that went back doesn't block for ever.
        assertEquals(DownloadGate.GO, UpdatePolicy.downloadGate(release, true, false, failed, t - 1))
    }

    @Test
    fun resumesOnlyAPartialFile() {
        assertEquals(0L, UpdatePolicy.resumeFrom(0, 1000))
        assertEquals(400L, UpdatePolicy.resumeFrom(400, 1000))
        assertEquals(0L, UpdatePolicy.resumeFrom(1000, 1000))
        assertEquals(0L, UpdatePolicy.resumeFrom(1200, 1000))
    }

    @Test
    fun checksTheApkBeforeAndroidsPrompt() {
        val pkg = "com.msp1974.vacompanion.odysseus"
        assertNull(UpdatePolicy.apkProblem(pkg, 1400029, release, pkg, 1400030, true))
        assertNull("unknown signature: Android decides", UpdatePolicy.apkProblem(pkg, 1400029, release, pkg, 1400030, null))
        assertTrue(UpdatePolicy.apkProblem(pkg, 1400029, release, null, null, null)!!.contains("readable"))
        assertTrue(UpdatePolicy.apkProblem(pkg, 1400029, release, "com.msp1974.vacompanion", 1400030, true)!!.contains("com.msp1974.vacompanion,"))
        assertTrue(UpdatePolicy.apkProblem(pkg, 1400029, release, pkg, 1400031, true)!!.contains("hub said"))
        assertTrue(UpdatePolicy.apkProblem(pkg, 1400030, release, pkg, 1400030, true)!!.contains("isn't newer"))
        assertTrue(UpdatePolicy.apkProblem(pkg, 1400029, release, pkg, 1400030, false)!!.contains("different key"))
    }

    @Test
    fun explainsInstallerStatuses() {
        assertNull(UpdatePolicy.installStatusMessage(UpdatePolicy.STATUS_SUCCESS, null))
        assertEquals("Install cancelled.", UpdatePolicy.installStatusMessage(UpdatePolicy.STATUS_FAILURE_ABORTED, "x"))
        assertTrue(UpdatePolicy.installStatusMessage(UpdatePolicy.STATUS_FAILURE_CONFLICT, null)!!.contains("different key"))
        assertEquals("Install failed: boom", UpdatePolicy.installStatusMessage(1, "boom"))
        assertEquals("Install failed.", UpdatePolicy.installStatusMessage(1, null))
    }

    @Test
    fun matchesAndroidsStatusValues() {
        assertEquals(android.content.pm.PackageInstaller.STATUS_SUCCESS, UpdatePolicy.STATUS_SUCCESS)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED, UpdatePolicy.STATUS_FAILURE_BLOCKED)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, UpdatePolicy.STATUS_FAILURE_ABORTED)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID, UpdatePolicy.STATUS_FAILURE_INVALID)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, UpdatePolicy.STATUS_FAILURE_CONFLICT)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE, UpdatePolicy.STATUS_FAILURE_STORAGE)
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, UpdatePolicy.STATUS_FAILURE_INCOMPATIBLE)
    }

    @Test
    fun sizeLabels() {
        assertEquals("31 MB", UpdatePolicy.sizeLabel(31_457_280))
        assertEquals("0.5 MB", UpdatePolicy.sizeLabel(500_000))
    }
}
