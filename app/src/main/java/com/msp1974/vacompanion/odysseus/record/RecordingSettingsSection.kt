package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: Menu → Odysseus → Recording (docs/odysseus/recording.md). Called from
// odysseus/OdysseusSettingsLayout.kt.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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

@Composable
fun RecordingSettingsSection() {
    RecorderSettings.init(LocalContext.current)
    val ui by RecorderController.state.collectAsState()
    val enabled = RecorderSettings.enabled
    Text(
        "Recording", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Toggle(
        enabled, "Recording",
        "Record conversations (tap the record button) and voice notes (hold it 1 s) for Athena. " +
            "The audio goes only to your Odysseus hub. A red pill shows while anything records.",
    ) {
        RecorderSettings.enabled = it
        if (!it) RecorderController.onRecordingDisabled()
    }
    Toggle(
        RecorderSettings.clockButton, "Record button on the clock",
        "A small record button in the clock page's bottom right. Also in the quick actions sheet.",
        enabled = enabled,
    ) { RecorderSettings.clockButton = it }
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

@Composable
private fun Toggle(checked: Boolean, title: String, detail: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
