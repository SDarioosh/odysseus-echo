package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: what the alarm clock shows over every screen (docs/odysseus/alarms-timers.md),
// composed by OdysseusOverlays:
//   - TimersPill: this device's timers at the top centre, each with its countdown ("Pasta 4:59");
//     a paused one shows a pause sign. Cancel, pause or add time by voice (Home Assistant).
//   - RingingCard: while an alarm or timer rings, a full-screen card with Stop (and Snooze for an
//     alarm). A wake word or stop word stops it too.
// Lock mode covers both (LockOverlay is drawn after them); nothing rings while locked.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.OdysseusColors
import kotlinx.coroutines.delay

private val PillShape = RoundedCornerShape(20.dp)
private val PillBg = OdysseusColors.Panel.copy(alpha = 0.92f)
private val Muted = OdysseusColors.muted(OdysseusColors.Fg, OdysseusColors.Panel)

/** This device's timers with their countdowns, at the top centre; nothing without timers. */
@Composable
fun TimersPill() {
    val timers by AlarmClock.timers.collectAsState()
    if (timers.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timers) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.background(PillBg, PillShape).border(1.dp, OdysseusColors.Border, PillShape)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // The three soonest fit on the Echo Show 5's width.
            timers.sortedBy { it.leftMs(now) }.take(3).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (t.running) Icons.Filled.Timer else Icons.Filled.Pause, contentDescription = null,
                        tint = if (t.running) OdysseusColors.Red else Muted, modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    t.name?.let {
                        Text(
                            TimerRules.title(t), color = OdysseusColors.Fg, fontSize = 18.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    Text(
                        TimerRules.countdown(t.leftMs(now)), color = if (t.running) OdysseusColors.Fg else Muted,
                        fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (timers.size > 3) Text("+${timers.size - 3}", color = Muted, fontSize = 18.sp)
        }
    }
}

/** While an alarm or timer rings: a full-screen card with Stop, and Snooze for an alarm. */
@Composable
fun RingingCard() {
    val due by AlarmClock.ringing.collectAsState()
    val d = due ?: return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f))
            // Taps on the card's background do nothing (they don't reach the dashboard).
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (d.kind == Due.Kind.ALARM) Icons.Filled.Alarm else Icons.Filled.Timer, contentDescription = null,
                tint = OdysseusColors.Red, modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                d.title, color = OdysseusColors.Fg, fontSize = 40.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (d.kind == Due.Kind.ALARM) d.detail else "${d.detail} · done",
                color = Muted, fontSize = 22.sp,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (d.kind == Due.Kind.ALARM) {
                    OutlinedButton(onClick = { AlarmClock.snooze() }, modifier = Modifier.height(64.dp).width(180.dp)) {
                        Text("Snooze", fontSize = 24.sp, color = OdysseusColors.Fg)
                    }
                }
                Button(
                    onClick = { AlarmClock.stop() }, modifier = Modifier.height(64.dp).width(180.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OdysseusColors.Red),
                ) {
                    Text("Stop", fontSize = 24.sp, color = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Or say the wake word", color = Muted, fontSize = 16.sp)
        }
    }
}
