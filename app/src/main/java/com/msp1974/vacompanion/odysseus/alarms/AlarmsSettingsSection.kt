package com.msp1974.vacompanion.odysseus.alarms

// Odysseus fork: Menu → Odysseus → "Alarms and timers" (docs/odysseus/alarms-timers.md). Called with
// one line from OdysseusSettingsLayout. Lists this device's alarms (from Odysseus) with a Delete
// button each, and its timers (from Home Assistant). New ones are set by voice.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime

@Composable
fun AlarmsSettingsSection() {
    val colors = MaterialTheme.colorScheme
    val alarms by AlarmClock.alarms.collectAsState()
    val timers by AlarmClock.timers.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }

    Text("Alarms and timers", fontSize = 18.sp, color = colors.primary, modifier = Modifier.padding(top = 12.dp))
    Text(
        "Set them by voice: \"wake me up at 7\", \"set an alarm for 6:30 on weekdays\", \"set a 10 minute " +
            "pasta timer\". Both ring only on this device, with the alarm sound and alarm volume of Settings → Audio. " +
            "Alarms are kept by Odysseus and still ring when it is down; timers are Home Assistant's. " +
            "Stop a ring on the screen or with the wake word. Nothing rings while Lock mode is locked.",
        fontSize = 14.sp, color = colors.onSurfaceVariant,
    )

    val now = ZonedDateTime.now()
    val sorted = alarms.sortedBy { AlarmRules.nextRing(it, now)?.toEpochSecond() ?: Long.MAX_VALUE }
    if (sorted.isEmpty()) Text("No alarms.", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
    sorted.forEach { a ->
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    AlarmRules.timeLabel(a.time) + (a.label?.let { " · $it" } ?: ""),
                    fontSize = 18.sp,
                )
                Text(AlarmRules.whenLabel(a, LocalDate.now()), fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
            OutlinedButton(
                enabled = busy == null,
                onClick = {
                    busy = a.id
                    scope.launch {
                        val problem = Alarms.delete(a)
                        note = problem?.let { "Not deleted: $it" } ?: "Deleted the ${AlarmRules.timeLabel(a.time)} alarm."
                        busy = null
                    }
                },
            ) { Text(if (busy == a.id) "Deleting…" else "Delete") }
        }
    }
    if (note.isNotEmpty()) Text(note, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

    if (timers.isNotEmpty()) {
        Text("Timers", fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
        val nowMs = System.currentTimeMillis()
        timers.forEach { t ->
            Text(
                "${TimerRules.title(t)}: ${TimerRules.countdown(t.leftMs(nowMs))} left" + (if (t.running) "" else " (paused)"),
                fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
