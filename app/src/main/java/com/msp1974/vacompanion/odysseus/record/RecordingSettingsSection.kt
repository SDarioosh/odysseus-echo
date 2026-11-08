package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: Menu → Odysseus → Recording (docs/odysseus/recording.md). Called from
// odysseus/OdysseusSettingsLayout.kt.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.OdysseusColors
import com.msp1974.vacompanion.odysseus.managed.ManagedNote
import com.msp1974.vacompanion.odysseus.managed.ManagedSettings
import com.msp1974.vacompanion.odysseus.managed.ManagedSwitchRow

@Composable
fun RecordingSettingsSection() {
    RecorderSettings.init(LocalContext.current)
    val ui by RecorderController.state.collectAsState()
    val enabled = RecorderSettings.enabled
    Text(
        "Recording", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    ManagedSwitchRow(
        enabled, "Recording",
        "Record conversations (tap the record button) and voice notes (hold it 1 s) for Athena. " +
            "The audio goes only to your Odysseus hub. A red pill shows while anything records.",
        managedKey = "record.enabled",
    ) { RecorderSettings.enabled = it }
    ManagedSwitchRow(
        RecorderSettings.clockButton, "Record button on the clock",
        "A small record button in the clock page's bottom right. Also in the quick actions sheet.",
        enabled = enabled,
        managedKey = "record.clock_button",
    ) { RecorderSettings.clockButton = it }
    PrivacyButtonChoice(enabled)
    Text(RecordingText.queue(ui.queue), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    ui.uploadProblem?.let { Text(it, fontSize = 15.sp, color = OdysseusColors.Red) }
    ui.warning?.let { Text(it, fontSize = 15.sp, color = OdysseusColors.Red) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { RecorderController.uploadNow() }) { Text("Upload now") }
    }
    if (!RecorderController.isInstalled) {
        Text(
            "The recorder starts with the app's background service.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Privacy button": Record or Mute (record/RecordButton.kt, docs/odysseus/recording.md). */
@Composable
private fun PrivacyButtonChoice(recordingEnabled: Boolean) {
    val hasButton = RecordButton.hasPrivacyButton(LocalContext.current)
    val mode = RecorderSettings.privacyButton
    Column {
        Text("Privacy button", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (m in PrivacyButtonMode.entries) {
                RadioButton(
                    selected = mode == m,
                    onClick = { RecorderSettings.privacyButton = m },
                    enabled = hasButton && !ManagedSettings.isManaged("record.privacy_button"),
                )
                Text(
                    if (m == PrivacyButtonMode.RECORD) "Record" else "Mute",
                    fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
        val detail = when {
            !hasButton -> "This device has no privacy button: the setting does nothing here."
            mode == PrivacyButtonMode.MUTE -> "The button mutes the microphone (\"Microphone off\"). If that shows " +
                "right after choosing Mute, press the button once."
            !recordingEnabled -> "Recording is off, so the button mutes the microphone."
            else -> "Press: record, press again to stop. Hold 1 s: voice note. The button no longer " +
                "mutes: use Mic Mute in the quick actions. With the screen off or another app in " +
                "front, a hold counts as a press."
        }
        Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ManagedNote("record.privacy_button")
    }
}
