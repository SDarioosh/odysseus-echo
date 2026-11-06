package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the recorder on screen (docs/odysseus/recording.md). These only show
// RecorderController.state and send presses; the recorder itself lives in the process.
//   - RecordingPill: the red "REC 12:34" / "NOTE 0:42" pill over every screen while anything
//     records; it keeps the screen on, and a tap on it is a press (stop).
//   - RecordQuickAction: the record button in the quick actions sheet.
//   - ClockRecordButton: the small record button on the clock page.
// Buttons: a tap is a press; holding 1 s is a hold (fired while still held, letting go does nothing).

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.OdysseusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val PillShape = RoundedCornerShape(8.dp)
private val PillBg = OdysseusColors.Bg.copy(alpha = 0.92f)

/**
 * Tap = [RecorderController.press]; held for [RecorderStateMachine.HOLD_MS] =
 * [RecorderController.hold], fired while still held. Letting go after a hold does nothing.
 */
fun Modifier.recordGestures(): Modifier = pointerInput(Unit) {
    detectTapGestures(onPress = {
        val released = withTimeoutOrNull(RecorderStateMachine.HOLD_MS) { tryAwaitRelease() }
        when (released) {
            null -> {
                RecorderController.hold()
                tryAwaitRelease()
            }
            true -> RecorderController.press()
            false -> Unit   // the gesture was taken by something else (a scroll)
        }
    })
}

/** The red pill over every screen while anything records. Composed once in MainActivity. */
@Composable
fun RecordingPill(modifier: Modifier = Modifier) {
    val ui by RecorderController.state.collectAsState()
    val active = ui.recorder != RecorderState.Idle

    // Keep the screen on while recording (cleared when the pill goes).
    val view = LocalView.current
    DisposableEffect(active) {
        if (active) view.keepScreenOn = true
        onDispose { if (active) view.keepScreenOn = false }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000)
        }
    }

    val dot by rememberInfiniteTransition(label = "rec-dot").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rec-dot-alpha",
    )

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = active,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(PillBg, PillShape)
                    .border(2.dp, OdysseusColors.Red, PillShape)
                    .recordGestures()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .alpha(dot)
                        .background(OdysseusColors.Red, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    RecordingText.pill(ui.recorder, now) ?: "",
                    color = OdysseusColors.Red,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun actionLabel(state: RecorderState): String = when (state) {
    RecorderState.Idle -> "Record"
    is RecorderState.Recording -> "Stop"
    is RecorderState.Note -> "Stop note"
}

/** The record button in the quick actions sheet (ui/components/QuickActionsSheet.kt hook). */
@Composable
fun RecordQuickAction() {
    RecorderSettings.init(LocalContext.current)
    if (!RecorderSettings.enabled) return
    val ui by RecorderController.state.collectAsState()
    val active = ui.recorder != RecorderState.Idle
    val colors = MaterialTheme.colorScheme
    // Same look as upstream's QuickActionButton, with press and hold.
    Surface(
        modifier = Modifier.size(80.dp).recordGestures(),
        shape = RoundedCornerShape(12.dp),
        color = if (active) colors.primaryContainer else colors.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp),
        ) {
            Icon(
                imageVector = if (active) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = "Record (hold for a voice note)",
                modifier = Modifier.size(24.dp),
                tint = OdysseusColors.Red,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                actionLabel(ui.recorder),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

/** The small record button on the clock page (odysseus/home/ClockPage.kt hook). */
@Composable
fun ClockRecordButton(modifier: Modifier = Modifier) {
    RecorderSettings.init(LocalContext.current)
    if (!RecorderSettings.enabled || !RecorderSettings.clockButton) return
    val ui by RecorderController.state.collectAsState()
    val active = ui.recorder != RecorderState.Idle
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), CircleShape)
            .border(1.dp, OdysseusColors.Red.copy(alpha = if (active) 1f else 0.5f), CircleShape)
            .recordGestures(),
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            contentDescription = if (active) "Stop recording" else "Record (hold for a voice note)",
            tint = OdysseusColors.Red,
            modifier = Modifier.size(30.dp),
        )
    }
}
