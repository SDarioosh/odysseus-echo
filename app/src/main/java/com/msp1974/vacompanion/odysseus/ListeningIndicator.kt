package com.msp1974.vacompanion.odysseus

// Odysseus fork: on-screen voice activity indicator.
//
// Upstream VACA relies on the HA dashboard (View Assist) or the wake sound to show that the device
// is listening. On a quiet or muted Echo Show that leaves no feedback at all, so this draws a
// native overlay above whatever layout is showing (dashboard or blank screen):
//   - a glowing bar along the bottom edge, pulsing while listening and faster once speech is heard
//   - a pill with the current phase: "Listening…", "Thinking…", "Answering…"
// It's driven only by the satellite's own pipeline stages, so it needs nothing from HA.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.satellite.PipelineStage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VoiceActivity {
    enum class Phase { IDLE, LISTENING, HEARING, THINKING, ANSWERING }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The hardware privacy switch (or anything else) has muted the microphone. */
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()
    fun setMicMuted(muted: Boolean) { _micMuted.value = muted }

    /** Called the moment a wake word is accepted, before the pipeline starts, so feedback is instant. */
    fun onWakeWord() {
        VoiceTurns.onTurnStarted()
        _phase.value = Phase.LISTENING
    }

    fun onIdle() {
        _phase.value = Phase.IDLE
    }

    fun onPipelineStage(stage: PipelineStage) {
        _phase.value = when (stage) {
            PipelineStage.INIT, PipelineStage.COMPLETED_TTS, PipelineStage.ENDED -> Phase.IDLE
            PipelineStage.STARTING, PipelineStage.STARTED, PipelineStage.LISTENING ->
                // A TTS-only pipeline (announcement) also passes STARTING/STARTED; don't flash
                // "Listening" for it if we're already answering.
                if (_phase.value == Phase.ANSWERING) Phase.ANSWERING else Phase.LISTENING
            PipelineStage.VOICE_STARTED -> Phase.HEARING
            PipelineStage.VOICE_STOPPED, PipelineStage.AWAITING_RESPONSE, PipelineStage.AWAITING_TTS -> Phase.THINKING
            PipelineStage.STARTING_TTS, PipelineStage.STREAMING_TTS -> Phase.ANSWERING
        }
    }

    /** Pipelines end with ENDED, but never leave the indicator up if one gets stuck. */
    const val MAX_VISIBLE_MS = 90_000L
}

// The Odysseus web app's colours (OdysseusTheme.kt). The indicator floats over the dashboard, so it
// keeps the dark theme's look whatever the app theme is.
private val ListeningColor = OdysseusColors.Fg
private val ThinkingColor = OdysseusColors.Red
private val AnsweringColor = OdysseusColors.Green
private val PillBg = OdysseusColors.Bg.copy(alpha = 0.92f)
private val PillShape = RoundedCornerShape(8.dp)

@Composable
fun ListeningIndicator(modifier: Modifier = Modifier) {
    val phase by VoiceActivity.phase.collectAsState()
    val micMuted by VoiceActivity.micMuted.collectAsState()
    val context = LocalContext.current

    // The Echo Show's privacy button mutes the microphone in hardware; Android reports it as a
    // microphone mute (AudioService.setMicrophoneMuteFromSwitch). Show it on screen.
    DisposableEffect(context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        VoiceActivity.setMicMuted(audio.isMicrophoneMute)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                VoiceActivity.setMicMuted(audio.isMicrophoneMute)
            }
        }
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(phase) {
        if (phase != VoiceActivity.Phase.IDLE) {
            delay(VoiceActivity.MAX_VISIBLE_MS)
            VoiceActivity.onIdle()
        }
    }

    val (label, color, periodMs) = when (phase) {
        VoiceActivity.Phase.LISTENING -> Triple("Listening…", ListeningColor, 900)
        VoiceActivity.Phase.HEARING -> Triple("Listening…", ListeningColor, 350)
        VoiceActivity.Phase.THINKING -> Triple("Thinking…", ThinkingColor, 1200)
        VoiceActivity.Phase.ANSWERING -> Triple("Answering…", AnsweringColor, 1600)
        VoiceActivity.Phase.IDLE -> Triple("", ListeningColor, 1000)
    }

    val pulse by rememberInfiniteTransition(label = "voice-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "voice-pulse-alpha",
    )

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = micMuted,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(PillBg, PillShape)
                    .border(1.dp, OdysseusColors.Red, PillShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.MicOff, contentDescription = null, tint = OdysseusColors.Red, modifier = Modifier.size(24.dp))
                Text("  Microphone off", color = OdysseusColors.Red, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }
        AnimatedVisibility(
            visible = phase != VoiceActivity.Phase.IDLE && !micMuted,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    color = color,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .background(PillBg, PillShape)
                        .border(1.dp, OdysseusColors.Border, PillShape)
                        .padding(horizontal = 22.dp, vertical = 8.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .alpha(pulse)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, color))
                        ),
                )
            }
        }
    }
}
