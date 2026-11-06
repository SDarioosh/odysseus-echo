package com.msp1974.vacompanion.odysseus.home.today

// Odysseus fork: the Today page, right of the clock (swipe left from the clock). Read-only: the
// headline of Odysseus's Today insight, today's calendar, to-dos due today or overdue, Athena's
// open action items, and "and N more" where they don't all fit. Data comes from
// TodayRepository through HomePageContext.today; TodayRefresh (composed by HomeHost) keeps it
// fresh. Sized for the Echo Show 5 (960×480 px, about 790×395 dp) and to be read from across a
// kitchen: large rows, few of them, the rest counted.

import android.text.format.DateFormat
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
                    Body(view.data, now, is24h, Modifier.weight(1f).fillMaxWidth())
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
private fun Body(data: TodayData, now: Long, is24h: Boolean, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    BoxWithConstraints(modifier) {
        val leftRows = rows(maxHeight - LABEL_HEIGHT)
        // The right column has two sections; an empty one takes one row for its "nothing" line.
        val empties = (if (data.todos.isEmpty()) 1 else 0) + (if (data.actionItems.isEmpty()) 1 else 0)
        val rightRows = rows(maxHeight - LABEL_HEIGHT * 2) - empties
        val (todoShown, aiShown) = TodayFormat.fitColumn(rightRows, data.todos.size, data.actionItems.size)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1.15f)) {
                Events(data.events, TodayFormat.fitEvents(leftRows, data.events.size), now, zone, is24h)
            }
            Column(Modifier.weight(1f)) {
                Todos(data, todoShown, today)
                ActionItems(data, aiShown)
            }
        }
    }
}

private fun rows(height: Dp): Int = (height / ROW_HEIGHT).toInt().coerceAtLeast(0)

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
private fun Events(events: List<TodayEvent>, shown: Int, now: Long, zone: ZoneId, is24h: Boolean) {
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
                TodayFormat.eventTime(e, zone, is24h),
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
private fun Bullet(color: Color) {
    Box(Modifier.padding(end = 12.dp).size(10.dp).background(color, CircleShape))
}

@Composable
private fun Todos(data: TodayData, shown: Int, today: LocalDate) {
    val colors = MaterialTheme.colorScheme
    SectionLabel("To-do")
    if (data.todos.isEmpty()) {
        Quiet("Nothing due")
        return
    }
    data.todos.take(shown).forEach { t ->
        val due = TodayFormat.dueLabel(t, today)
        val dueColor = if (due == "Overdue") colors.error else colors.primary
        ItemRow {
            Bullet(if (due == "Overdue") colors.error else colors.onSurfaceVariant)
            Title(t.title, Modifier.weight(1f, fill = false))
            if (due != null) {
                Text(
                    due, style = MaterialTheme.typography.labelLarge, color = dueColor, maxLines = 1,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .background(dueColor.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
    More(TodayFormat.more(data.counts.todosOpen, data.todos.size, shown))
}

@Composable
private fun ActionItems(data: TodayData, shown: Int) {
    val colors = MaterialTheme.colorScheme
    SectionLabel("From conversations")
    if (data.actionItems.isEmpty()) {
        Quiet("No open action items")
        return
    }
    data.actionItems.take(shown).forEach { a ->
        ItemRow {
            Bullet(colors.secondary)
            Title(a.title, Modifier.weight(1f, fill = false))
        }
    }
    More(TodayFormat.more(data.counts.actionItemsOpen, data.actionItems.size, shown))
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
