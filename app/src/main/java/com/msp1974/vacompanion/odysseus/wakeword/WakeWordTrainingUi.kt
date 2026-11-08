package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork: Menu → Odysseus → Wake word training (docs/odysseus/wake-word-training.md).
// Called from odysseus/OdysseusSettingsLayout.kt. The screen only calls WakeClips and shows its
// state; its one setting is WakeClipSettings.catchMisses.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.msp1974.vacompanion.odysseus.OdysseusColors
import com.msp1974.vacompanion.odysseus.managed.ManagedSwitchRow

@Composable
fun WakeWordTrainingSection() {
    WakeClips.init(LocalContext.current)
    val status by WakeClips.status.collectAsState()
    var open by remember { mutableStateOf(false) }

    Text(
        "Wake word training", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "Help “Hey Athena” hear you: record yourself saying it, and Odysseus keeps the clips to retrain " +
            "the wake word on your real voice. Clips go only to your Odysseus hub.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { WakeClips.resetTraining(); open = true }) { Text("Record clips (about 1 minute)") }
    ManagedSwitchRow(
        WakeClipSettings.catchMisses, "Catch missed wake words",
        "When it nearly heard “Hey Athena” but didn't wake, and you then said it again, keep both " +
            "tries (about 3 seconds each) as clips. Nothing else is kept.",
    ) { WakeClipSettings.catchMisses = it }
    Text(statusText(status), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    status.problem?.let { Text(it, fontSize = 15.sp, color = OdysseusColors.Red) }
    if (status.waiting > 0) {
        OutlinedButton(onClick = { WakeClips.uploadNow() }) { Text("Send now") }
    }

    if (open) TrainingDialog(onClose = { WakeClips.stopTraining(); open = false })
}

private fun statusText(s: ClipsStatus): String {
    val sent = when (s.sentTotal) { 0 -> "No clips sent yet"; 1 -> "1 clip sent"; else -> "${s.sentTotal} clips sent" }
    return if (s.waiting > 0) "$sent, ${s.waiting} waiting to send." else "$sent."
}

@Composable
private fun TrainingDialog(onClose: () -> Unit) {
    val state by WakeClips.training.collectAsState()
    // Never leave the device in training, where it doesn't wake.
    DisposableEffect(Unit) { onDispose { WakeClips.stopTraining() } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Wake word training", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                when {
                    state.running -> Recording(state)
                    state.outcomes.isNotEmpty() || state.problem != null -> Summary(state)
                    else -> Intro()
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.running) {
                        OutlinedButton(onClick = { WakeClips.stopTraining() }) { Text("Stop") }
                    } else {
                        Button(onClick = { WakeClips.resetTraining(); WakeClips.startTraining() }) {
                            Text(if (state.outcomes.isEmpty()) "Start" else "Record 10 more")
                        }
                        OutlinedButton(onClick = onClose) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Intro() {
    Text(
        "You'll be asked to say “Hey Athena” 10 times, each a little differently. Say it once each " +
            "time the circle turns green. Stand where you usually talk to this device; move around " +
            "when asked.",
        fontSize = 18.sp, textAlign = TextAlign.Center,
    )
    Text(
        "The device won't answer while you record. Every clip goes to your Odysseus hub only, where " +
            "you can listen to it, delete it, or download all of them to retrain the wake word.",
        fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Others in the house can do it too: clips of every voice help.",
        fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Recording(state: TrainingState) {
    Text("Take ${state.take} of ${state.total}", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(state.prompt, fontSize = 24.sp, textAlign = TextAlign.Center)
    val color = if (state.recording) OdysseusColors.Green else MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (state.recording) "Say\n“Hey Athena”" else "Get ready…",
            fontSize = 22.sp, textAlign = TextAlign.Center,
            color = if (state.recording) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // The take's time running out.
    var started by remember(state.take, state.recording) { mutableStateOf(false) }
    LaunchedEffect(state.take, state.recording) { started = state.recording }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(if (started) WakeClips.TAKE_MS.toInt() else 0, easing = LinearEasing),
        label = "take",
    )
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(0.6f))
    state.outcomes.lastOrNull()?.let { Text("Last take: ${outcomeText(it)}", fontSize = 16.sp) }
}

@Composable
private fun Summary(state: TrainingState) {
    state.problem?.let { Text(it, fontSize = 17.sp, color = OdysseusColors.Red, textAlign = TextAlign.Center) }
    val kept = state.outcomes.count { !it.silent }
    val heard = state.outcomes.count { !it.silent && it.heard }
    if (state.outcomes.isNotEmpty()) {
        Text("$kept clips kept. The wake word caught $heard of them.", fontSize = 20.sp, textAlign = TextAlign.Center)
        Text(
            if (kept > heard) "The ones it missed are the most useful for training." else "Thanks: every clip helps.",
            fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((i, o) in state.outcomes.withIndex()) {
                Text("${i + 1}. ${o.prompt}: ${outcomeText(o)}", fontSize = 15.sp)
            }
        }
    }
}

private fun outcomeText(o: TakeOutcome): String = when {
    o.silent -> "nothing heard, not kept"
    o.heard -> "caught (%.2f)".format(o.score)
    else -> "missed (%.2f), kept".format(o.score)
}
