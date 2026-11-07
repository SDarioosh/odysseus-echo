package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: the clock page's elements, one composable per ElementType, each drawing the
// variant its ClockElement asks for. Colours come from MaterialTheme (set by ClockThemed), so an
// element never reads ClockTheme's hex strings itself; fonts for the time come from the theme.

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.done.DoneTarget
import com.msp1974.vacompanion.odysseus.home.HomePageContext
import com.msp1974.vacompanion.odysseus.home.today.DoneRow
import com.msp1974.vacompanion.odysseus.home.today.Tag
import com.msp1974.vacompanion.odysseus.home.today.TodayData
import com.msp1974.vacompanion.odysseus.home.today.TodayDone
import com.msp1974.vacompanion.odysseus.home.today.TodayEvent
import com.msp1974.vacompanion.odysseus.home.today.TodayFormat
import com.msp1974.vacompanion.odysseus.home.today.TodayView
import com.msp1974.vacompanion.odysseus.home.weather.WeatherBadge
import com.msp1974.vacompanion.odysseus.home.weather.WeatherForecasts
import com.msp1974.vacompanion.odysseus.home.weather.WeatherNow
import com.msp1974.vacompanion.odysseus.home.weather.drawableFor
import com.msp1974.vacompanion.sendspin.SendspinArtwork
import com.msp1974.vacompanion.sendspin.SendspinNowPlaying
import com.msp1974.vacompanion.odysseus.player.BarPlayer
import com.msp1974.vacompanion.odysseus.player.FullPlayer
import com.msp1974.vacompanion.odysseus.player.LinePlayer
import com.msp1974.vacompanion.odysseus.player.MiniPlayer
import com.msp1974.vacompanion.odysseus.player.OnControl
import com.msp1974.vacompanion.odysseus.player.PillPlayer
import com.msp1974.vacompanion.odysseus.player.RowPlayer
import com.msp1974.vacompanion.odysseus.player.commandFor
import com.msp1974.vacompanion.odysseus.player.toPlayerState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Everything an element may need, besides its ClockElement. */
data class ClockFrame(
    val page: HomePageContext,
    val theme: ClockTheme,
    /** The time's size at scale 1, from the screen. */
    val baseTimeSize: TextUnit,
    val nowMs: Long,
    val is24h: Boolean,
    val artwork: Bitmap?,
) {
    val today: TodayData? get() = (page.today as? TodayView.Ready)?.data
}

/** Text alignment for multi-line elements: follows the column of their anchor. */
private val LocalClockAlign = compositionLocalOf { TextAlign.Center }

// A pause normally ends the Sendspin stream, so the track lingers a while to let it be resumed
// (same as ui/components/NowPlayingOverlay.kt).
private const val PAUSED_LINGER_MS = 20_000L

private fun TextUnit.times(f: Float): TextUnit = (value * f).sp

@Composable
fun ClockElementView(element: ClockElement, frame: ClockFrame, modifier: Modifier = Modifier) {
    val align = when (element.anchor.col) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
    androidx.compose.runtime.CompositionLocalProvider(LocalClockAlign provides align) {
        Box(modifier) {
            when (element.type) {
                ElementType.TIME -> TimeElement(element, frame)
                ElementType.DATE -> DateElement(element, frame)
                ElementType.WEATHER -> frame.page.weather?.let { WeatherElement(element, it) }
                ElementType.NOW_PLAYING -> NowPlayingElement(element, frame)
                ElementType.HEADLINE -> HeadlineElement(element, frame)
                ElementType.NEXT_EVENT -> NextEventElement(element, frame)
                ElementType.TODAY_PANEL -> TodayPanelElement(element, frame)
                ElementType.SUMMARY -> SummaryElement(element, frame)
                ElementType.STATUS -> StatusElement(frame.page)
                ElementType.RECORD -> com.msp1974.vacompanion.odysseus.record.ClockRecordButton()
                ElementType.UPDATE -> com.msp1974.vacompanion.odysseus.update.UpdateBanner()
            }
        }
    }
}

// ---- Time and date ------------------------------------------------------------------------

private fun fmt(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
private fun best(skeleton: String, ms: Long) =
    fmt(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), ms)

@Composable
private fun Prompt(command: String, s: Float = 1f) {
    val colors = MaterialTheme.colorScheme
    Row {
        Text("$ ", fontSize = 17.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
        Text(command, fontSize = 17.sp.times(s), color = colors.onBackground, maxLines = 1)
    }
}

@Composable
private fun TimeElement(element: ClockElement, frame: ClockFrame) {
    val colors = MaterialTheme.colorScheme
    val theme = frame.theme
    val wanted = frame.baseTimeSize.times(element.scale)
    val time = fmt(if (frame.is24h) "H:mm" else "h:mm", frame.nowMs)
    val amPm = if (frame.is24h) null else fmt("a", frame.nowMs)
    val family = ClockFontFamilies.family(theme.timeFont)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
    // Shrink the time when it doesn't fit its column (a wide font beside a panel).
    val size = with(density) {
        val timePx = measurer.measure(
            time, TextStyle(fontFamily = family, fontWeight = FontWeight(theme.timeWeight), fontSize = wanted, letterSpacing = theme.timeSpacing.em),
        ).size.width
        val amPmPx = if (amPm == null) 0f else measurer.measure(amPm, TextStyle(fontSize = wanted.times(0.22f))).size.width + 8.dp.toPx()
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.MAX_VALUE
        val needed = timePx + amPmPx
        if (needed > room && needed > 0f) wanted.times((room / needed) * 0.98f) else wanted
    }
    Column(horizontalAlignment = alignmentOf(LocalClockAlign.current)) {
        if (theme.terminal) Prompt("date")
        if (element.style == Variants.TIME_WEEKDAY) {
            Text(
                fmt("EEEE", frame.nowMs).uppercase(), color = colors.primary, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = time,
                fontFamily = family,
                fontWeight = FontWeight(theme.timeWeight),
                fontSize = size,
                lineHeight = size,
                letterSpacing = theme.timeSpacing.em,
                color = if (theme.terminal) colors.primary else colors.onBackground,
                maxLines = 1,
            )
            if (amPm != null) {
                Text(
                    text = amPm,
                    fontFamily = if (theme.timeFont == ClockFonts.BEBAS) ClockFontFamilies.family(theme.timeFont) else null,
                    fontSize = size.times(0.22f),
                    color = if (theme.amPmAccent) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, bottom = (size.value * 0.07f).dp),
                )
            }
        }
    }
}
}

@Composable
private fun DateElement(element: ClockElement, frame: ClockFrame) {
    val colors = MaterialTheme.colorScheme
    val s = element.scale
    val ms = frame.nowMs
    when (element.style) {
        Variants.DATE_CAPS -> Text(
            best("EEEEdMMMM", ms).replace(", ", " · ").uppercase(),
            fontSize = 17.sp.times(s), letterSpacing = 4.sp, fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant, maxLines = 1,
        )
        Variants.DATE_WEEKDAY -> Text(
            fmt("EEEE", ms).uppercase(), fontSize = 15.sp.times(s), letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold, color = colors.primary, maxLines = 1,
        )
        Variants.DATE_MONTH_DAY -> Text(best("MMMMd", ms), fontSize = 22.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
        Variants.DATE_ELEGANT -> Text(
            best("MMMMd", ms), fontFamily = ClockFontFamilies.family(frame.theme.timeFont),
            fontStyle = FontStyle.Italic, fontSize = 30.sp.times(s), color = colors.onBackground, maxLines = 1,
        )
        Variants.DATE_ISO -> Text(fmt("EEE yyyy-MM-dd", ms), fontSize = 17.sp.times(s), color = colors.tertiary, maxLines = 1)
        else -> Text(
            best("EEEEdMMMM", ms), style = MaterialTheme.typography.titleLarge.let { it.copy(fontSize = it.fontSize.times(s)) },
            color = colors.onSurfaceVariant, maxLines = 1,
        )
    }
}

private fun alignmentOf(a: TextAlign): Alignment.Horizontal = when (a) {
    TextAlign.Start -> Alignment.Start
    TextAlign.End -> Alignment.End
    else -> Alignment.CenterHorizontally
}

// ---- Weather -------------------------------------------------------------------------------

private fun degrees(v: Double) = "${v.roundToInt()}°"

private fun conditionWords(c: String) = when (c) {
    "partlycloudy" -> "partly cloudy"
    "clear-night" -> "clear"
    "lightning-rainy" -> "storms"
    "lightning" -> "thunder"
    "snowy-rainy" -> "sleet"
    "windy-variant" -> "windy"
    "exceptional" -> "warning"
    else -> c.replace('-', ' ')
}

@Composable
private fun WeatherIcon(w: WeatherNow, size: Dp, tint: Color) {
    Icon(
        painter = painterResource(drawableFor(WeatherForecasts.icon(w.condition, w.isNight))),
        contentDescription = w.condition, tint = tint, modifier = Modifier.size(size),
    )
}

@Composable
private fun WeatherElement(element: ClockElement, w: WeatherNow) {
    val colors = MaterialTheme.colorScheme
    val s = element.scale
    val highLow = listOfNotNull(w.high?.let { "H ${degrees(it)}" }, w.low?.let { "L ${degrees(it)}" }).joinToString(" · ")
    when (element.style) {
        Variants.WEATHER_COMPACT -> Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(w, (30 * s).dp, colors.primary)
            Spacer(Modifier.width(10.dp))
            w.temperature?.let { Text(degrees(it), fontSize = 24.sp.times(s), color = colors.onBackground, maxLines = 1) }
            if (highLow.isNotEmpty()) {
                Text("  $highLow", fontSize = 15.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        Variants.WEATHER_LARGE -> Column(horizontalAlignment = alignmentOf(LocalClockAlign.current)) {
            WeatherIcon(w, (64 * s).dp, colors.onBackground)
            w.temperature?.let {
                Text(degrees(it), fontSize = 44.sp.times(s), lineHeight = 46.sp.times(s), fontWeight = FontWeight.Light, color = colors.onBackground, maxLines = 1)
            }
            if (highLow.isNotEmpty()) Text(highLow, fontSize = 16.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
        }
        Variants.WEATHER_TEXT -> Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(w, (22 * s).dp, colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            val parts = listOfNotNull(
                w.temperature?.let { degrees(it) + " " + conditionWords(w.condition) },
                w.high?.let { "high ${degrees(it)}" }, w.low?.let { "low ${degrees(it)}" },
            )
            Text(parts.joinToString(" · "), fontSize = 17.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
        }
        else -> WeatherBadge(w)
    }
}

// ---- Now playing ---------------------------------------------------------------------------

/** The cover art: pushed over the Sendspin connection first, else fetched from its URL. */
@Composable
fun rememberArtwork(nowPlaying: SendspinNowPlaying): Bitmap? {
    val pushed = nowPlaying.artwork
    val url = nowPlaying.artworkUrl
    val fetched by produceState<Bitmap?>(initialValue = null, url, pushed) {
        value = if (pushed == null) url?.let { SendspinArtwork.load(it) } else null
    }
    return pushed ?: fetched
}

/**
 * Shows [content] while a track plays, keeps it dimmed as "Paused" for a while after the stream
 * stops, then animates it away (as the original clock did).
 */
@Composable
private fun Lingering(nowPlaying: SendspinNowPlaying, content: @Composable (SendspinNowPlaying) -> Unit) {
    var visible by remember { mutableStateOf(nowPlaying.active && nowPlaying.hasContent) }
    LaunchedEffect(nowPlaying.active, nowPlaying.hasContent) {
        if (nowPlaying.active && nowPlaying.hasContent) {
            visible = true
        } else {
            delay(PAUSED_LINGER_MS)
            visible = false
        }
    }
    var shown by remember { mutableStateOf(nowPlaying) }
    shown = if (nowPlaying.hasContent) nowPlaying else shown.copy(active = false, playing = false)
    AnimatedVisibility(
        visible = visible && shown.hasContent,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Box(Modifier.alpha(if (shown.active) 1f else 0.6f)) { content(shown) }
    }
}

@Composable
fun nowPlayingVisible(nowPlaying: SendspinNowPlaying): Boolean {
    var visible by remember { mutableStateOf(nowPlaying.active && nowPlaying.hasContent) }
    LaunchedEffect(nowPlaying.active, nowPlaying.hasContent) {
        if (nowPlaying.active && nowPlaying.hasContent) visible = true else { delay(PAUSED_LINGER_MS); visible = false }
    }
    return visible
}

@Composable
private fun NowPlayingElement(element: ClockElement, frame: ClockFrame) {
    val onCommand = frame.page.onSendspinCommand
    Lingering(frame.page.nowPlaying) { np ->
        // The shared player pieces (odysseus/player/) over the neutral state; their controls go
        // back as the same Sendspin commands. "Paused" follows Sendspin's stream, as before.
        val state = np.toPlayerState()
        val onControl: OnControl = { control, value -> np.commandFor(control, value)?.let(onCommand) }
        val paused = !np.active
        when (element.style) {
            Variants.NP_PILL -> PillPlayer(state, frame.artwork, onControl, element.scale, paused)
            Variants.NP_MINI -> MiniPlayer(state, frame.artwork, onControl, element.scale, paused)
            Variants.NP_LINE -> LinePlayer(state, onControl, element.scale, paused)
            Variants.NP_BAR -> BarPlayer(state, onControl, paused) { BarCounts(frame) }
            Variants.NP_FULL -> FullPlayer(state, frame.artwork, onControl, element.scale, paused)
            else -> RowPlayer(state, frame.artwork, onControl, paused)
        }
    }
}

/** The open counts at the far end of the now-playing bar. */
@Composable
private fun BarCounts(frame: ClockFrame) {
    frame.today?.let { data ->
        val (t, a) = ClockToday.counts(data)
        val label = ClockToday.countsLabel(t, a)
        if (label.isNotEmpty()) Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, modifier = Modifier.padding(end = 16.dp))
    }
}

// ---- Odysseus: headline, next event, Today panel, summary -----------------------------------

@Composable
private fun HeadlineElement(element: ClockElement, frame: ClockFrame) {
    val data = frame.today ?: return
    val line = ClockToday.headline(data) ?: return
    val colors = MaterialTheme.colorScheme
    val italic = element.style == Variants.HEADLINE_ITALIC
    Text(
        line,
        fontSize = (if (italic) 21.sp else 19.sp).times(element.scale),
        fontWeight = if (italic) FontWeight.Normal else FontWeight.Light,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontFamily = if (italic) ClockFontFamilies.family(frame.theme.timeFont) else null,
        color = if (italic) colors.onBackground else colors.primary,
        textAlign = LocalClockAlign.current,
        maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = (560 * element.scale).dp),
    )
}

private fun eventTime(e: TodayEvent, frame: ClockFrame): String =
    TodayFormat.eventTime(e, ZoneId.systemDefault(), frame.is24h, today = today(frame))

private fun today(frame: ClockFrame): LocalDate = Instant.ofEpochMilli(frame.nowMs).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable
private fun NextEventElement(element: ClockElement, frame: ClockFrame) {
    val data = frame.today ?: return
    val colors = MaterialTheme.colorScheme
    val s = element.scale
    val next = ClockToday.nextEvent(data.events, frame.nowMs)
    when (element.style) {
        Variants.NEXT_CARD -> {
            Column(
                modifier = Modifier
                    .widthIn(max = (340 * s).dp)
                    .background(colors.surfaceVariant, RoundedCornerShape(20.dp))
                    .border(1.dp, colors.onBackground.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                if (next == null) {
                    // Nothing timed left today: the headline and what's open instead.
                    Text("TODAY", fontSize = 13.sp.times(s), letterSpacing = 2.sp, color = colors.onSurfaceVariant, maxLines = 1)
                    Text(
                        ClockToday.headline(data) ?: "Nothing else on the calendar", fontSize = 19.sp.times(s),
                        fontWeight = FontWeight.Medium, color = colors.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    val (t, a) = ClockToday.counts(data)
                    ClockToday.countsLabel(t, a).takeIf { it.isNotEmpty() }?.let {
                        Text(it, fontSize = 15.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                    }
                    return@Column
                }
                Text(
                    "NEXT UP · " + ClockToday.relative(next, frame.nowMs).uppercase(), fontSize = 13.sp.times(s),
                    letterSpacing = 2.sp, color = colors.onSurfaceVariant, maxLines = 1,
                )
                Text(next.title, fontSize = 26.sp.times(s), fontWeight = FontWeight.Medium, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(eventTime(next, frame), next.location?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    fontSize = 16.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val after = ClockToday.eventAfter(data.events, next)
                val (t, a) = ClockToday.counts(data)
                val then = listOfNotNull(
                    after?.let { "Then ${eventTime(it, frame)} ${it.title}" },
                    ClockToday.countsLabel(t, a).takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                if (then.isNotEmpty()) {
                    Text(then, fontSize = 15.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Variants.NEXT_LINE -> Column {
            if (frame.theme.terminal) Prompt("next")
            if (next == null) {
                Text("  nothing left today", fontSize = 17.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
            } else {
                Row {
                    Text("  " + eventTime(next, frame) + " ", fontSize = 17.sp.times(s), color = colors.primary, maxLines = 1)
                    Text(next.title, fontSize = 17.sp.times(s), color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(" (" + ClockToday.relative(next, frame.nowMs) + ")", fontSize = 17.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        else -> {
            if (next == null) return
            Column(horizontalAlignment = alignmentOf(LocalClockAlign.current)) {
                Text(
                    ClockToday.relative(next, frame.nowMs).uppercase(), fontSize = 13.sp.times(s), letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium, color = colors.primary, maxLines = 1,
                )
                Text(
                    next.title + " · " + eventTime(next, frame), fontSize = 19.sp.times(s), fontWeight = FontWeight.Medium,
                    color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = (320 * s).dp),
                )
            }
        }
    }
}

@Composable
private fun TodayPanelElement(element: ClockElement, frame: ClockFrame) {
    val colors = MaterialTheme.colorScheme
    val view = frame.page.today
    if (view == TodayView.NotLinked) return
    val style = element.style
    val fill = if (element.fillHeight) Modifier.fillMaxHeight() else Modifier
    val container = when (style) {
        Variants.PANEL_CARD -> Modifier.fillMaxWidth().then(fill).background(colors.surfaceVariant, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp, vertical = 20.dp)
        Variants.PANEL_COLUMN -> Modifier.fillMaxWidth().then(fill)
        else -> Modifier.fillMaxWidth()
    }
    Row(Modifier.then(fill)) {
        if (style == Variants.PANEL_COLUMN) {
            Box(Modifier.width(1.dp).then(if (element.fillHeight) Modifier.fillMaxHeight() else Modifier.height(300.dp)).background(colors.onSurfaceVariant.copy(alpha = 0.3f)))
            Spacer(Modifier.width(28.dp))
        }
        Column(container) {
            when (view) {
                is TodayView.Ready -> PanelBody(element, frame, view.data)
                TodayView.Loading -> Text("Loading today…", fontSize = 16.sp, color = colors.onSurfaceVariant)
                is TodayView.Unreachable -> Text("Can't reach Odysseus", fontSize = 16.sp, color = colors.onSurfaceVariant)
                TodayView.NotLinked -> Unit
            }
        }
    }
}

@Composable
private fun ColumnScope.PanelBody(element: ClockElement, frame: ClockFrame, data: TodayData) {
    val colors = MaterialTheme.colorScheme
    val s = element.scale
    val terminal = element.style == Variants.PANEL_TERMINAL
    val hubToday = runCatching { LocalDate.parse(data.date.take(10)) }.getOrNull() ?: today(frame)
    val marks = frame.page.todayDone
    val todos = marks.rows(data.todos, TodayDone::todoTarget, { it.title }, { it is DoneTarget.Todo })
    val maxTodos = if (element.fillHeight) 4 else 3
    val headline = ClockToday.headline(data)

    if (terminal) {
        Prompt("today --todo", s)
        if (todos.isEmpty()) {
            val open = ClockToday.counts(data).first
            Text("  nothing due today" + if (open > 0) " ($open open)" else "", fontSize = 17.sp.times(s), color = colors.onSurfaceVariant)
        }
        todos.take(maxTodos).forEachIndexed { index, row ->
            val due = row.item?.let { TodayFormat.dueLabel(it, hubToday) }
            DoneRow(
                row, index, if (due == "Overdue") colors.error else colors.primary, frame.page,
                titleStyle = TextStyle(fontSize = 17.sp.times(s)), rowHeight = (32 * s).dp, textCheck = true,
            ) { if (due == "Overdue") Text(" overdue", fontSize = 17.sp.times(s), color = colors.error, maxLines = 1) }
        }
        if (headline != null) {
            Prompt("today --insight", s)
            Text("  $headline", fontSize = 17.sp.times(s), color = colors.tertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$ ", fontSize = 17.sp.times(s), color = colors.onSurfaceVariant)
            Box(Modifier.width(10.dp).height(20.dp).background(colors.primary))
        }
        return
    }

    val column = element.style == Variants.PANEL_COLUMN
    Text("TODAY", fontSize = 13.sp.times(s), letterSpacing = 3.sp, fontWeight = FontWeight.Bold, color = colors.primary, maxLines = 1)
    if (headline != null) {
        Text(
            headline,
            fontSize = (if (column) 21.sp else 19.sp).times(s),
            fontFamily = if (column) ClockFontFamilies.family(frame.theme.timeFont) else null,
            fontStyle = if (column) FontStyle.Italic else FontStyle.Normal,
            fontWeight = if (column) FontWeight.Normal else FontWeight.Medium,
            color = colors.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
    }
    // The events still to come (or going on), at most two.
    val upcoming = data.events.filter { !TodayFormat.isPast(it, frame.nowMs) }.take(2)
    upcoming.forEachIndexed { i, ev ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(eventTime(ev, frame), fontSize = 15.sp.times(s), fontWeight = FontWeight.Bold, color = if (i == 0) colors.primary else colors.onSurfaceVariant, maxLines = 1)
            Text(
                "  " + ev.title + if (i == 0 && !ev.allDay) " · " + ClockToday.relative(ev, frame.nowMs) else "",
                fontSize = 15.sp.times(s), color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (upcoming.isNotEmpty()) {
        Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(colors.onSurfaceVariant.copy(alpha = 0.25f)))
    }
    val (openTodos, openAi) = ClockToday.counts(data)
    if (todos.isEmpty()) {
        // The hub lists only to-dos due today, overdue or pinned; its count is every open one.
        Text(
            "Nothing due today" + if (openTodos > 0) " · $openTodos open" else "",
            fontSize = 15.sp.times(s), color = colors.onSurfaceVariant,
        )
    }
    todos.take(maxTodos).forEachIndexed { index, row ->
        val due = row.item?.let { TodayFormat.dueLabel(it, hubToday) }
        DoneRow(
            row, index, if (due == "Overdue") colors.error else colors.onSurfaceVariant, frame.page,
            titleStyle = TextStyle(fontSize = 16.sp.times(s)), rowHeight = (40 * s).dp, checkSize = (20 * s).dp,
        ) { if (due == "Overdue") Tag("Overdue", colors.error) }
    }
    val more = if (todos.isEmpty()) 0 else TodayFormat.more(openTodos, todos.size, minOf(todos.size, maxTodos))
    if (more > 0) Text("and $more more", fontSize = 14.sp.times(s), color = colors.onSurfaceVariant)
    if (openAi > 0) {
        if (element.fillHeight) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(10.dp))
        Text(
            (if (openAi == 1) "1 action item" else "$openAi action items") + if (column) "" else " from conversations",
            fontSize = 14.sp.times(s), color = colors.primary, maxLines = 1,
            modifier = Modifier.background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SummaryElement(element: ClockElement, frame: ClockFrame) {
    val data = frame.today ?: return
    val colors = MaterialTheme.colorScheme
    val s = element.scale
    val (t, a) = ClockToday.counts(data)
    when (element.style) {
        Variants.SUMMARY_CHIP -> {
            val label = ClockToday.countsLabel(t, a)
            if (label.isEmpty()) return
            Text(
                label, fontSize = 14.sp.times(s), color = colors.primary, maxLines = 1,
                modifier = Modifier.background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        else -> Column(horizontalAlignment = alignmentOf(LocalClockAlign.current)) {
            ClockToday.nextEvent(data.events, frame.nowMs)?.let {
                Text("${it.title} ${ClockToday.relative(it, frame.nowMs)}", fontSize = 15.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
            }
            val label = ClockToday.countsLabel(t, a)
            if (label.isNotEmpty()) Text(label, fontSize = 15.sp.times(s), color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ---- Status --------------------------------------------------------------------------------

/** Shown only when something needs attention; empty otherwise. */
@Composable
private fun StatusElement(context: HomePageContext) {
    val colors = MaterialTheme.colorScheme
    val problem = when {
        !context.isNetworkConnected -> "Wi-Fi disconnected"
        !context.isServerConnected -> "Home Assistant disconnected"
        else -> null
    }
    if (problem == null && !context.isDND) return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (problem != null) Text(problem, style = MaterialTheme.typography.labelLarge, color = colors.error)
        if (context.isDND) Text("Do not disturb", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    }
}
