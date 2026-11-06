package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: the Today page, right of the clock (swipe left from the clock): the headline of
// Odysseus's Today insight, today's calendar, to-dos due today or overdue, Athena's open action
// items, and "and N more" where they don't all fit. Data comes from TodayRepository through
// HomePageContext.today; TodayRefresh (composed by HomeHost) keeps it fresh. A tap on a to-do or
// action item row marks it done ("Done · Undo" for 10 s; HomePageContext.todayDone and
// onTodayDone/onTodayUndo, today/TodayDone). Sized for the Echo Show 5 (960×480 px, about
// 790×395 dp) and to be read from across a kitchen: large rows, few of them, the rest counted;
// rows that can be tapped are TAP_ROW_HEIGHT tall, the full column wide.

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.msp1974.vacompanion.odysseus.done.DoneMark
import com.msp1974.vacompanion.odysseus.done.DoneTarget
import com.msp1974.vacompanion.odysseus.done.ShownRow
import com.msp1974.vacompanion.odysseus.home.HomePage
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** One item row (titleLarge text) and one section label, for working out what fits. */
private val ROW_HEIGHT = 40.dp
private val LABEL_HEIGHT = 32.dp
/** A row with a Done control: Material's 48 dp touch target, the whole row wide. */
private val TAP_ROW_HEIGHT = 48.dp
private val CHECK_SIZE = 30.dp

private const val MINUTE_MS = 60_000L

object TodayPage : HomePage {
    override val id: String = "today"

    /** In the page list: turned on (default) and linked to Odysseus. Compose state. */
    val available: Boolean get() = HomeSettings.todayEnabled && TodayRepository.linked

    @Composable
    override fun Content(context: HomePageContext, modifier: Modifier) {
        val colors = MaterialTheme.colorScheme
        // Recomposes once a minute, so past events dim and labels follow midnight.
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(MINUTE_MS - value % MINUTE_MS + 50)
            }
        }
        val is24h = DateFormat.is24HourFormat(LocalContext.current)
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            val view = context.today
            Header(now, (view as? TodayView.Ready)?.data)
            when (view) {
                is TodayView.Ready -> {
                    Body(view.data, context, now, is24h, Modifier.weight(1f).fillMaxWidth())
                    if (view.stale) {
                        Text(
                            text = "Odysseus unreachable · " +
                                TodayFormat.updatedAt(view.fetchedAtMs, ZoneId.systemDefault(), is24h),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.error,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
                TodayView.Loading -> Message("Loading today…", null, Modifier.weight(1f))
                TodayView.NotLinked -> Message(
                    "Not linked to Odysseus",
                    "Link this device in Menu → Odysseus to see today here.",
                    Modifier.weight(1f),
                )
                is TodayView.Unreachable -> Message("Can't reach Odysseus", view.message, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Header(now: Long, data: TodayData?) {
    val colors = MaterialTheme.colorScheme
    val locale = Locale.getDefault()
    val day = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(now))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium,
            color = colors.primary, maxLines = 1,
        )
        Spacer(Modifier.width(16.dp))
        Text(day, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant, maxLines = 1)
    }
    // The headline; without one, the top focus item of the Today insight.
    val line = data?.headline ?: data?.focus?.firstOrNull()?.let { f ->
        "Focus: ${f.title}" + (f.why?.let { " — $it" } ?: "")
    }
    if (line != null) {
        Text(
            line, style = MaterialTheme.typography.titleLarge, color = colors.onBackground,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Body(data: TodayData, context: HomePageContext, now: Long, is24h: Boolean, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    // The device's day for event times (shown in its zone); the hub's day (the data's date) for
    // what is due today or overdue, as the hub chose the to-dos by it.
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val hubToday = runCatching { LocalDate.parse(data.date.take(10)) }.getOrNull() ?: today
    val marks = context.todayDone
    // The rows as shown: done ones stay in place for their undo window, then leave.
    val todos = marks.rows(data.todos, TodayDone::todoTarget, { it.title }, { it is DoneTarget.Todo })
    val actionItems = marks.rows(data.actionItems, TodayDone::actionItemTarget, { it.title }, { it is DoneTarget.ActionItem })
    BoxWithConstraints(modifier) {
        val leftRows = rows(maxHeight - LABEL_HEIGHT, ROW_HEIGHT)
        // The right column has two sections; an empty one takes one row for its "nothing" line.
        val empties = (if (todos.isEmpty()) 1 else 0) + (if (actionItems.isEmpty()) 1 else 0)
        val rightRows = rows(maxHeight - LABEL_HEIGHT * 2, TAP_ROW_HEIGHT) - empties
        val (todoShown, aiShown) = TodayFormat.fitColumn(rightRows, todos.size, actionItems.size)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1.15f)) {
                Events(data.events, TodayFormat.fitEvents(leftRows, data.events.size), now, zone, is24h, today)
            }
            Column(Modifier.weight(1f)) {
                Todos(todos, todoShown, hubToday, context)
                if (todos.isNotEmpty()) {
                    More(TodayFormat.more(data.counts.todosOpen?.minus(marks.hiddenAmong(data.todos.map(TodayDone::todoTarget))), todos.size, todoShown))
                }
                ActionItems(actionItems, aiShown, context)
                if (actionItems.isNotEmpty()) {
                    More(
                        TodayFormat.more(
                            data.counts.actionItemsOpen?.minus(marks.hiddenAmong(data.actionItems.map(TodayDone::actionItemTarget))),
                            actionItems.size, aiShown,
                        )
                    )
                }
            }
        }
    }
}

private fun rows(height: Dp, rowHeight: Dp): Int = (height / rowHeight).toInt().coerceAtLeast(0)

@Composable
private fun SectionLabel(text: String) {
    Box(Modifier.height(LABEL_HEIGHT), contentAlignment = Alignment.CenterStart) {
        Text(
            text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary, maxLines = 1,
        )
    }
}

@Composable
private fun ItemRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.height(ROW_HEIGHT).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Quiet(text: String) {
    ItemRow {
        Text(
            text, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@Composable
private fun More(n: Int) {
    val label = TodayFormat.moreLabel(n) ?: return
    ItemRow {
        Text(
            label, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

@Composable
private fun Events(events: List<TodayEvent>, shown: Int, now: Long, zone: ZoneId, is24h: Boolean, today: LocalDate) {
    val colors = MaterialTheme.colorScheme
    SectionLabel("Calendar")
    if (events.isEmpty()) {
        Quiet("Nothing on the calendar")
        return
    }
    // Fira Code is monospaced: "12:30 PM" is 8 characters, "12:30" 5.
    val timeWidth = if (is24h) 84.dp else 128.dp
    events.take(shown).forEach { e ->
        val past = TodayFormat.isPast(e, now)
        ItemRow(Modifier.alpha(if (past) 0.45f else 1f)) {
            Text(
                TodayFormat.eventTime(e, zone, is24h, today = today),
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary, maxLines = 1,
                modifier = Modifier.width(timeWidth),
            )
            Title(e.title, Modifier.weight(1f, fill = false))
            e.location?.takeIf { it.isNotBlank() }?.let {
                Text(
                    " · $it", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
    More(events.size - shown)
}

@Composable
private fun Todos(rows: List<ShownRow<TodayTodo>>, shown: Int, today: LocalDate, context: HomePageContext) {
    val colors = MaterialTheme.colorScheme
    SectionLabel("To-do")
    if (rows.isEmpty()) {
        Quiet("Nothing due")
        return
    }
    rows.take(shown).forEachIndexed { index, row ->
        val due = row.item?.let { TodayFormat.dueLabel(it, today) }
        val tagColor = if (due == "Overdue") colors.error else colors.primary
        DoneRow(row, index, if (due == "Overdue") colors.error else colors.onSurfaceVariant, context) {
            if (due != null) Tag(due, tagColor)
        }
    }
}

@Composable
private fun ActionItems(rows: List<ShownRow<TodayActionItem>>, shown: Int, context: HomePageContext) {
    SectionLabel("From conversations")
    if (rows.isEmpty()) {
        Quiet("No open action items")
        return
    }
    rows.take(shown).forEachIndexed { index, row ->
        DoneRow(row, index, MaterialTheme.colorScheme.secondary, context) {}
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1,
        modifier = Modifier
            .padding(start = 10.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * A to-do or action item row with its Done control: a ring to tick at the start, the title, and
 * at the end the due tag, or once tapped "Done · Undo" (a tap on the row undoes), "Undoing…",
 * or a short reason. Tapping anywhere on the row counts. A row without a ref or id can't be done.
 */
@Composable
private fun DoneRow(
    row: ShownRow<*>,
    index: Int,
    ringColor: Color,
    context: HomePageContext,
    tag: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val mark = row.mark
    val ticked = mark?.ticked == true
    val doable = when (val t = row.target) {
        is DoneTarget.Todo -> t.ref.isNotBlank()
        is DoneTarget.ActionItem -> t.id.isNotBlank()
        is DoneTarget.Reminder -> false
    }
    val onTap: (() -> Unit)? = when {
        !doable -> null
        mark == null || mark.phase == DoneMark.Phase.FAILED -> { { context.onTodayDone(row.target, row.title, index) } }
        mark.phase == DoneMark.Phase.DONE && mark.canUndo -> { { context.onTodayUndo(row.target) } }
        else -> null
    }
    Row(
        modifier = Modifier
            .height(TAP_ROW_HEIGHT)
            .fillMaxWidth()
            .then(
                if (onTap != null) Modifier.clickable(
                    role = Role.Checkbox,
                    onClickLabel = if (ticked) "Undo" else "Mark done",
                    onClick = onTap,
                ) else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp)
                .size(CHECK_SIZE)
                .then(
                    if (ticked) Modifier.background(colors.primary, CircleShape)
                    else Modifier.border(2.dp, if (doable) ringColor else ringColor.copy(alpha = 0.4f), CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (ticked) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
        }
        Text(
            row.title, style = MaterialTheme.typography.titleLarge,
            color = if (ticked) colors.onSurfaceVariant else colors.onBackground,
            textDecoration = if (ticked) TextDecoration.LineThrough else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        when {
            mark == null -> tag()
            mark.phase == DoneMark.Phase.FAILED -> mark.reason?.let { Tag(it, colors.error) }
            mark.phase == DoneMark.Phase.UNDOING -> Status("Undoing…", null)
            mark.phase == DoneMark.Phase.SENDING -> Status("Done", null)
            mark.phase == DoneMark.Phase.DONE -> Status(
                mark.reason ?: "Done",
                if (mark.canUndo) "Undo" else null,
            )
            else -> Unit
        }
    }
}

/** "Done · Undo" at the end of a ticked row. */
@Composable
private fun Status(text: String, action: String?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1)
        if (action != null) {
            Text(" · ", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, maxLines = 1)
            Text(
                action, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = colors.primary, maxLines = 1,
            )
        }
    }
}

@Composable
private fun Message(title: String, detail: String?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.onBackground, textAlign = TextAlign.Center)
        if (detail != null) {
            Text(
                detail, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
