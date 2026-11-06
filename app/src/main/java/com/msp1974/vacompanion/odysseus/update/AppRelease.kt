package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: the release the hub offers (GET <voice base>/app/latest, contract C2 in
// docs/odysseus/plans/2026-11-today-updates-announce-record.md). Plain Kotlin, unit-tested.
//
//   {"app": "odysseus-echo", "version_name": "0.14.0+odysseus.30", "version_code": 1400030,
//    "size": 31457280, "sha256": "9f2c...", "notes": "Kiosk settings; Today page."}
//
// The device compares version_code, never the name: the semver library ignores "+odysseus.N".

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppRelease(
    val app: String = "",
    @SerialName("version_name") val versionName: String = "",
    @SerialName("version_code") val versionCode: Long = 0,
    val size: Long = 0,
    val sha256: String = "",
    val notes: String? = null,
) {
    /** The sha256 in lower case, the form files are named and compared by. */
    val sha: String get() = sha256.trim().lowercase()

    /** Why this release can't be used, or null when it can. */
    fun problem(): String? = when {
        app != APP_NAME -> "the hub offers a release for \"$app\", not $APP_NAME"
        versionCode <= 0 -> "the release has no version code"
        size <= 0 -> "the release has no size"
        !SHA256_RE.matches(sha) -> "the release has no valid sha256"
        else -> null
    }

    companion object {
        const val APP_NAME = "odysseus-echo"
        private val SHA256_RE = Regex("[0-9a-f]{64}")
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Parses the body of a 200 from /app/latest; throws on malformed JSON. */
        fun parse(body: String): AppRelease = json.decodeFromString(serializer(), body)
    }
}
