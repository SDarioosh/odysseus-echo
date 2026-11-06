package com.msp1974.vacompanion.odysseus

// Odysseus fork: the turn card. After a voice turn, shows what it did in Odysseus:
//   - what it added or completed ("Call the dentist — To-dos · Tomorrow, 9:00 AM", "✓ Send …"),
//   - up to three of its sources as chips; a tap opens the page in an overlay with a close button,
//   - while Odysseus waits for a spoken "yes", the question it asked.
// It asks the hub for the turn (GET /turns/latest, OdysseusLink.fetchLatestTurn) when the answer
// starts and again when the turn ends; an older hub without that feed (404) gets GET /actions.
// The card shows for SHOW_MS (AWAITING_SHOW_MS while a confirmation waits), until tapped, or until
// the next wake word. Nothing is fetched when the device isn't linked to Odysseus.

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.viewinterop.AndroidView
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

object VoiceTurns {
    const val SHOW_MS = 15_000L
    /** Long enough to read the question and answer it in the follow-up HA opens. */
    const val AWAITING_SHOW_MS = 60_000L
    /** Odysseus may record a turn a moment after the answer starts; ask again once. */
    private const val RETRY_MS = 1_500L
    /** A little before the wake word, so clocks a second apart don't hide a turn. */
    private const val CLOCK_SLACK_MS = 2_000L

    private val _shown = MutableStateFlow<TurnCardState?>(null)
    val shown: StateFlow<TurnCardState?> = _shown.asStateFlow()

    private val _openLink = MutableStateFlow<TurnLink?>(null)
    /** The source page open in the overlay, if any. */
    val openLink: StateFlow<TurnLink?> = _openLink.asStateFlow()

    @Volatile var turnStartedAt = 0L
        private set

    /** This device's id (APPConfig.uuid), so a turn another satellite answered isn't shown here. */
    @Volatile var deviceId = ""

    /** The turn (its turnStartedAt) the card in [shown] was fetched for; 0 = none yet. */
    @Volatile private var shownFor = 0L

    /** False once the hub answered 404 for /turns/latest; then /actions is used. Reset on relink. */
    @Volatile private var turnFeed = true

    /** A wake word: a new turn, so the previous card and page go. */
    fun onTurnStarted() {
        turnStartedAt = System.currentTimeMillis()
        _shown.value = null
        _openLink.value = null
    }

    /** A follow-up HA opened (e.g. after a confirmation question): keep the card it answers. */
    fun onFollowUpStarted() {
        turnStartedAt = System.currentTimeMillis()
    }

    fun dismiss() { _shown.value = null }

    fun open(link: TurnLink) { if (VoiceTurnApi.isWebUrl(link.url)) _openLink.value = link }

    fun closeLink() { _openLink.value = null }

    /** The link settings changed: try the turn feed again. */
    fun resetFeed() { turnFeed = true }

    /** Asks Odysseus what the current turn did and shows it. */
    suspend fun refresh() {
        val since = turnStartedAt
        if (since == 0L || !OdysseusLink.showActions || !OdysseusLink.isConfigured) return
        val from = since - CLOCK_SLACK_MS
        repeat(2) { attempt ->
            when (val found = fetch(from)) {
                Fetched.Stop -> return
                Fetched.NoTurn -> Unit
                is Fetched.Card -> {
                    if (turnStartedAt == since) { _shown.value = found.state; shownFor = since }
                    return
                }
            }
            if (attempt == 0) delay(RETRY_MS)
        }
    }

    /**
     * Whether the current turn waits for a spoken "yes" from this device (odysseus/followup/FollowUp.kt).
     * Uses the card already fetched for this turn (when the answer started); otherwise asks the hub,
     * whether or not the card is shown. A card tapped away counts as no.
     */
    suspend fun awaitingConfirmation(): Boolean {
        val since = turnStartedAt
        if (since == 0L || !OdysseusLink.isConfigured) return false
        if (shownFor == since) return _shown.value?.awaitingConfirmation != null
        val from = since - CLOCK_SLACK_MS
        repeat(2) { attempt ->
            when (val found = fetch(from)) {
                Fetched.Stop -> return false
                Fetched.NoTurn -> Unit
                is Fetched.Card -> {
                    if (OdysseusLink.showActions && turnStartedAt == since) { _shown.value = found.state; shownFor = since }
                    return found.state?.awaitingConfirmation != null
                }
            }
            if (attempt == 0) delay(RETRY_MS)
        }
        return false
    }

    private sealed interface Fetched {
        /** A turn was found; a null state means it did nothing worth showing (clears the card). */
        data class Card(val state: TurnCardState?) : Fetched
        data object NoTurn : Fetched
        data object Stop : Fetched
    }

    private suspend fun fetch(from: Long): Fetched {
        if (turnFeed) {
            when (val r = OdysseusLink.fetchLatestTurn(from)) {
                is OdysseusLink.TurnResult.Ok -> {
                    val turn = r.turn ?: return Fetched.NoTurn
                    if (!VoiceTurnApi.isFromDevice(turn, deviceId)) return Fetched.NoTurn
                    return Fetched.Card(VoiceTurnApi.cardState(turn))
                }
                is OdysseusLink.TurnResult.Failed -> { Timber.w("Odysseus turn: ${r.message}"); return Fetched.Stop }
                OdysseusLink.TurnResult.NotSupported -> {
                    Timber.i("Odysseus has no turn feed (404); using the actions feed")
                    turnFeed = false
                }
            }
        }
        return when (val r = OdysseusLink.fetchActions(from)) {
            is OdysseusLink.Result.Ok -> VoiceTurnApi.cardState(r.actions)?.let { Fetched.Card(it) } ?: Fetched.NoTurn
            is OdysseusLink.Result.Failed -> { Timber.w("Odysseus actions: ${r.message}"); Fetched.Stop }
        }
    }
}

// An Odysseus web app card (OdysseusTheme.kt): panel, 1 px border, 8 px corners. It floats over the
// dashboard, so it keeps the dark theme's look whatever the app theme is.
private val CardBg = OdysseusColors.Panel.copy(alpha = 0.95f)
private val CardShape = RoundedCornerShape(8.dp)
private val DoneGreen = OdysseusColors.Green
private val AddBlue = OdysseusColors.Red
private val AskAmber = Color(0xFFFFD54F)
private val Muted = OdysseusColors.muted(OdysseusColors.Fg, OdysseusColors.Panel)

@Composable
fun TurnCard(modifier: Modifier = Modifier) {
    OdysseusLink.init(LocalContext.current)
    val card by VoiceTurns.shown.collectAsState()
    val openLink by VoiceTurns.openLink.collectAsState()
    val phase by VoiceActivity.phase.collectAsState()

    // Ask when the answer starts (so the card shows while it's spoken) and again when the turn ends
    // (the indicator goes idle). A new phase cancels a fetch still running for the previous one.
    LaunchedEffect(phase) {
        if (phase == VoiceActivity.Phase.ANSWERING || phase == VoiceActivity.Phase.IDLE) VoiceTurns.refresh()
    }
    // The timer starts again whenever the card changes or its page is closed.
    LaunchedEffect(card, openLink) {
        val c = card ?: return@LaunchedEffect
        if (openLink != null) return@LaunchedEffect
        delay(if (c.awaitingConfirmation != null) VoiceTurns.AWAITING_SHOW_MS else VoiceTurns.SHOW_MS)
        VoiceTurns.dismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = card != null && openLink == null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it / 3 },
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        ) {
            card?.let { CardContent(it) }
        }
        openLink?.let { LinkOverlay(it) }
    }
}

@Composable
private fun CardContent(c: TurnCardState) {
    Column(
        modifier = Modifier
            .widthIn(min = 420.dp, max = 620.dp)
            .background(CardBg, CardShape)
            .border(1.dp, OdysseusColors.Border, CardShape)
            .clickable { VoiceTurns.dismiss() }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val actions = c.actions
        if (actions.isNotEmpty()) {
            val created = actions.count { it.action != "completed" }
            val done = actions.size - created
            Text(
                text = listOfNotNull(
                    if (created > 0) "Added" else null,
                    if (done > 0) "Completed" else null,
                ).joinToString(" · "),
                color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
            val rows = VoiceTurnApi.actionRows(c)
            actions.take(rows).forEach { ActionRow(it) }
            if (actions.size > rows) {
                Text("and ${actions.size - rows} more", color = Muted, fontSize = 15.sp)
            }
        }
        c.awaitingConfirmation?.let { AwaitingRow(it) }
        c.answer?.let {
            Text(it, color = Color(0xFFDADADA), fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (c.links.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c.links.forEach { link ->
                    AssistChip(
                        onClick = { VoiceTurns.open(link) },
                        label = {
                            Text(VoiceTurnApi.linkLabel(link), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = Color.White,
                            leadingIconContentColor = AddBlue,
                        ),
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AwaitingRow(question: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, tint = AskAmber, modifier = Modifier.size(30.dp))
        Box(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(question, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Say \u201Cyes\u201D to confirm or \u201Cno\u201D to cancel", color = AskAmber, fontSize = 15.sp)
        }
    }
}

/** A source page over the whole screen, with a close button. Pages load in this WebView only. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LinkOverlay(link: TurnLink) {
    val context = LocalContext.current
    val webView = remember(link.url) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient() // follow links inside, never hand them to another app
            loadUrl(link.url)
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(CardBg).padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(Icons.Filled.Link, contentDescription = null, tint = AddBlue, modifier = Modifier.size(22.dp))
            Text(
                "  " + VoiceTurnApi.linkLabel(link),
                color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { VoiceTurns.closeLink() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ActionRow(a: VoiceAction) {
    val completed = a.action == "completed"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (completed) Icons.Filled.TaskAlt else iconFor(a.kind),
            contentDescription = null,
            tint = if (completed) DoneGreen else AddBlue,
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
