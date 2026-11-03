package com.msp1974.vacompanion.odysseus

// Odysseus fork: a card showing what a voice turn created or completed in Odysseus
// ("Added: Call the dentist — To-dos · Tomorrow, 9:00 AM", "✓ Send the spreadsheet to Alice").
// After each voice turn ends, the actions done since that turn's wake word are fetched from
// Odysseus (OdysseusLink.fetchActions). The card shows for SHOW_MS, until tapped, or until the
// next wake word. Nothing is fetched when the device isn't linked to Odysseus.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object VoiceActions {
    const val SHOW_MS = 15_000L
    /** Odysseus may record an action a moment after the turn's audio ends; ask again once. */
    private const val RETRY_MS = 1_500L

    private val _shown = MutableStateFlow<List<VoiceAction>>(emptyList())
    val shown: StateFlow<List<VoiceAction>> = _shown.asStateFlow()

    @Volatile var turnStartedAt = 0L
        private set

    fun onTurnStarted() {
        turnStartedAt = System.currentTimeMillis()
        _shown.value = emptyList()
    }

    fun dismiss() { _shown.value = emptyList() }

    /** After a turn: show what it did, if anything. */
    suspend fun onTurnEnded() {
        val since = turnStartedAt
        if (since == 0L || !OdysseusLink.showActions || !OdysseusLink.isConfigured) return
        // A little before the wake word, so clocks a second apart don't hide an action.
        val from = since - 2_000L
        repeat(2) { attempt ->
            when (val r = OdysseusLink.fetchActions(from)) {
                is OdysseusLink.Result.Ok -> if (r.actions.isNotEmpty()) {
                    if (turnStartedAt == since) _shown.value = r.actions
                    return
                }
                is OdysseusLink.Result.Failed -> {
                    Timber.w("Odysseus actions: ${r.message}")
                    return
                }
            }
            if (attempt == 0) delay(RETRY_MS)
        }
    }
}

// An Odysseus web app card (OdysseusTheme.kt): panel, 1 px border, 8 px corners. It floats over the
// dashboard, so it keeps the dark theme's look whatever the app theme is.
private val CardBg = OdysseusColors.Panel.copy(alpha = 0.95f)
private val CardShape = RoundedCornerShape(8.dp)
private val DoneColor = OdysseusColors.Green
private val AddColor = OdysseusColors.Red
private val Muted = OdysseusColors.muted(OdysseusColors.Fg, OdysseusColors.Panel)
/** Fits the Echo Show 5's 480 px height with room for the app's toasts. */
private const val MAX_ROWS = 4

@Composable
fun VoiceActionsCard(modifier: Modifier = Modifier) {
    OdysseusLink.init(LocalContext.current)
    val actions by VoiceActions.shown.collectAsState()
    val phase by VoiceActivity.phase.collectAsState()

    // A turn starts at its wake word (VoiceActivity.onWakeWord) and ends when the indicator goes
    // idle. A new phase cancels a fetch still running for the previous one.
    LaunchedEffect(phase) {
        if (phase == VoiceActivity.Phase.IDLE) VoiceActions.onTurnEnded()
    }
    LaunchedEffect(actions) {
        if (actions.isNotEmpty()) {
            delay(VoiceActions.SHOW_MS)
            VoiceActions.dismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = actions.isNotEmpty(),
            enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it / 3 },
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 420.dp, max = 620.dp)
                    .background(CardBg, CardShape)
                    .border(1.dp, OdysseusColors.Border, CardShape)
                    .clickable { VoiceActions.dismiss() }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val created = actions.count { it.action != "completed" }
                val done = actions.size - created
                Text(
                    text = listOfNotNull(
                        if (created > 0) "Added" else null,
                        if (done > 0) "Completed" else null,
                    ).joinToString(" · "),
                    color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                actions.take(MAX_ROWS).forEach { ActionRow(it) }
                if (actions.size > MAX_ROWS) {
                    Text("and ${actions.size - MAX_ROWS} more", color = Muted, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(a: VoiceAction) {
    val completed = a.action == "completed"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (completed) Icons.Filled.TaskAlt else iconFor(a.kind),
            contentDescription = null,
            tint = if (completed) DoneColor else AddColor,
            modifier = Modifier.size(30.dp),
        )
        Box(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = a.title.ifBlank { a.detail ?: "" },
                color = if (completed) Muted else OdysseusColors.Fg,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = subtitle(a)
            if (sub.isNotEmpty()) Text(sub, color = Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun iconFor(kind: String): ImageVector = when (kind) {
    "event" -> Icons.Filled.Event
    "reminder" -> Icons.Filled.Alarm
    "note" -> Icons.Filled.NoteAlt
    else -> Icons.Filled.Checklist
}

internal fun subtitle(a: VoiceAction, today: LocalDate = LocalDate.now()): String {
    val where = a.where?.takeIf { it.isNotBlank() } ?: when {
        a.source == "athena" -> "Athena"
        a.kind == "event" -> "Calendar"
        a.kind == "note" -> "Notes"
        else -> "To-dos"
    }
    val prefix = if (a.action == "completed") "Done" else null
    return listOfNotNull(prefix, where, a.due?.let { formatDue(it, a.allDay, today) }).joinToString(" · ")
}

/** "Today, 3:00 PM", "Tomorrow", "Friday, 12:00 PM", "Mon 12 Nov, 9:00 AM". */
internal fun formatDue(iso: String, allDay: Boolean, today: LocalDate = LocalDate.now()): String? {
    val zone = ZoneId.systemDefault()
    val (date, time) = runCatching {
        val t = OffsetDateTime.parse(iso).atZoneSameInstant(zone)
        t.toLocalDate() to (if (allDay) null else t.toLocalTime())
    }.recoverCatching {
        LocalDate.parse(iso.take(10)) to null
    }.getOrNull() ?: return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    val dayText = when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days == -1L -> "Yesterday"
        days in 2..6 -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    return if (time == null) dayText
    else "$dayText, " + time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
}
