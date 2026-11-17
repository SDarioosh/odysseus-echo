package com.msp1974.vacompanion.odysseus.update

// Odysseus fork: Menu → Odysseus → Updates: the installed version, what the hub offers, "Check
// now", and "Install" once an update is downloaded (UpdateManager).

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.BuildConfig

@Composable
fun UpdateSettingsSection() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val state = UpdateManager.state
    val lastCheck = UpdateManager.lastCheckMs
    Text("Updates", fontSize = 18.sp, color = colors.primary, modifier = Modifier.padding(top = 12.dp))
    Text(
        "This app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        fontSize = 16.sp, color = colors.onSurface,
    )
    val status = when (state) {
        UpdateState.Idle -> "Checks Odysseus for updates a minute after starting, then daily."
        UpdateState.NotLinked -> "Link this device to Odysseus (above) to get updates from it."
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "Up to date."
        UpdateState.NoRelease -> "Odysseus has no app release uploaded."
        is UpdateState.Waiting -> "${state.release.versionName} is available. ${state.note}."
        is UpdateState.Downloading -> "Downloading ${state.release.versionName}: " +
            "${UpdatePolicy.sizeLabel(state.done)} of ${UpdatePolicy.sizeLabel(state.release.size)}"
        is UpdateState.Ready -> "Update ready: ${state.release.versionName}"
        is UpdateState.Failed -> state.message
    }
    Text(status, fontSize = 15.sp, color = if (state is UpdateState.Ready) colors.primary else colors.onSurfaceVariant)
    val release = when (state) {
        is UpdateState.Waiting -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        else -> null
    }
    release?.notes?.takeIf { it.isNotBlank() }?.let {
        Text("What's new: $it", fontSize = 14.sp, color = colors.onSurfaceVariant)
    }
    if (lastCheck > 0) {
        Text(
            "Last checked " + DateUtils.getRelativeTimeSpanString(lastCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            fontSize = 13.sp, color = colors.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state is UpdateState.Ready) {
            Button(onClick = { UpdateManager.install(context) }) { Text("Install") }
        }
        OutlinedButton(
            onClick = { UpdateManager.checkNow(context) },
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
        ) { Text("Check now") }
        if (state is UpdateState.Ready && !UpdateInstaller.canInstall(context)) {
            OutlinedButton(onClick = { UpdateInstaller.openInstallPermissionScreen(context) }) {
                Text("Allow installs")
            }
        }
    }
    UpdateManager.installMessage?.let { Text(it, fontSize = 14.sp, color = colors.onSurfaceVariant) }
    Text(
        "Android asks to confirm each install. Settings and the Odysseus link are kept.",
        fontSize = 13.sp, color = colors.onSurfaceVariant,
    )
}
