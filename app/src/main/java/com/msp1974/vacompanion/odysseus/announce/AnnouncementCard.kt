package com.msp1974.vacompanion.odysseus.announce

// Odysseus fork: the announcement / reminder card (docs/odysseus/announcements.md). Composed in
// MainActivity over any layout, beside the turn card. Shows the oldest waiting announcement:
// title, text and time; a reminder has a Dismiss button, and a tap anywhere on the card dismisses
// it too. A reminder of a to-do (with a `ref`) also has Done (ReminderDone: reminders/done, then
// the card goes; a failure shows a short reason). A card goes after AnnouncementCards.SHOW_MS (or when it expires), and is kept hidden
// while a voice turn is going on (VoiceTurnGuard), so it never covers the turn card or listening.

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
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
import com.msp1974.vacompanion.odysseus.VoiceActivity
import com.msp1974.vacompanion.odysseus.VoiceTurns
import kotlinx.coroutines.delay

private val CardBg = OdysseusColors.Panel.copy(alpha = 0.95f)
private val CardShape = RoundedCornerShape(8.dp)
private val ReminderAmber = Color(0xFFFFD54F)
private val Muted = OdysseusColors.muted(OdysseusColors.Fg, OdysseusColors.Panel)

@Composable
fun AnnouncementCard(modifier: Modifier = Modifier) {
    val cards by Announcements.cards.collectAsState()
    val doneStates by ReminderDone.states.collectAsState()
    val phase by VoiceActivity.phase.collectAsState()
    val turnCard by VoiceTurns.shown.collectAsState()
    val turnLink by VoiceTurns.openLink.collectAsState()

    var idleSince by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        if (phase == VoiceActivity.Phase.IDLE) idleSince = System.currentTimeMillis()
    }
    // Take each card away when its time is up.
    LaunchedEffect(cards) {
        val next = cards.nextDeadline() ?: return@LaunchedEffect
        delay((next - System.currentTimeMillis()).coerceAtLeast(0) + 50)
        Announcements.prune()
    }

    val hidden = VoiceTurnGuard.turnActive(phase, idleSince, VoiceTurns.turnStartedAt, turnCard != null || turnLink != null)
    val item = cards.current

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = item != null && !hidden,
            enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it / 3 },
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        ) {
            item?.let { CardContent(it.announcement, cards.more, doneStates[it.announcement.id]) }
        }
    }
}

@Composable
private fun CardContent(a: Announcement, more: Int, done: ReminderDoneState?) {
    val dismiss = {
        Announcements.dismiss(a.id)
        ReminderDone.forget(a.id)
    }
    Column(
        modifier = Modifier
            .widthIn(min = 420.dp, max = 620.dp)
            .background(CardBg, CardShape)
            .border(1.dp, OdysseusColors.Border, CardShape)
            .clickable { dismiss() }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (a.isReminder) Icons.Filled.Alarm else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = if (a.isReminder) ReminderAmber else OdysseusColors.Red,
                modifier = Modifier.size(30.dp),
            )
            Box(Modifier.width(14.dp))
            Text(
                text = (if (a.isReminder) "Reminder" else "Announcement") + " · " +
                    AnnouncementCards.timeLabel(a.at, System.currentTimeMillis()),
                color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
        }
        Text(
            a.heading, color = OdysseusColors.Fg, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        a.body?.let {
            Text(it, color = Color(0xFFDADADA), fontSize = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (a.isReminder || more > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Odysseus fork: Done (contract C5). Buttons 52 dp tall, to hit from arm's length.
                if (a.canMarkDone) {
                    val sending = done?.sending == true
                    Button(
                        onClick = { ReminderDone.done(a) },
                        enabled = !sending,
                        colors = ButtonDefaults.buttonColors(containerColor = OdysseusColors.Red, contentColor = Color.White),
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) { Text(if (sending) "Done…" else "Done", fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (a.isReminder) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.heightIn(min = 52.dp)) {
                        Text("Dismiss", fontSize = 17.sp)
                    }
                }
                if (more > 0) Text("and $more more", color = Muted, fontSize = 15.sp)
            }
        }
        done?.reason?.let {
            Text(it, color = OdysseusColors.Red, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
