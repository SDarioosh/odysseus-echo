package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: Menu → Odysseus → Clock face. A live preview of the clock (ClockPreview) beside
// five tabs:
//   Look     a gallery of whole designs (presets), drawn in their own colours and fonts; dimming.
//   Colours  colour schemes that go together (ClockDesign's ClockPalettes), a readability check,
//            and each colour on its own under "Fine-tune colours".
//   Fonts    font pairings (ClockFontPairs), then each font, weight and spacing under "Fine-tune".
//   Layout   what shows, listed the way it sits on the screen: tap one to place it, size it, pick
//            its look or move it in its stack; hidden ones below, one tap to add.
//   Night    the automatic night look.
// Every change is saved at once in HomeSettings.clockStyle and shows in the preview.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.home.music.MusicPage

private val WEIGHTS = listOf(200 to "Thin", 300 to "Light", 400 to "Regular", 500 to "Medium")
private val DIMS = listOf(1f to "Off", 0.8f to "80%", 0.6f to "60%", 0.4f to "40%")

enum class ClockTab(val label: String) { LOOK("Look"), COLOURS("Colours"), FONTS("Fonts"), LAYOUT("Layout"), NIGHT("Night") }

fun variantLabel(type: ElementType, v: String): String = when (type to v) {
    ElementType.TIME to Variants.TIME_PLAIN -> "Plain"
    ElementType.TIME to Variants.TIME_WEEKDAY -> "Weekday above"
    ElementType.DATE to Variants.DATE_LONG -> "Friday, November 6"
    ElementType.DATE to Variants.DATE_CAPS -> "FRIDAY · NOVEMBER 6"
    ElementType.DATE to Variants.DATE_WEEKDAY -> "FRIDAY"
    ElementType.DATE to Variants.DATE_MONTH_DAY -> "November 6"
    ElementType.DATE to Variants.DATE_ELEGANT -> "Italic"
    ElementType.DATE to Variants.DATE_ISO -> "Fri 2026-11-06"
    ElementType.WEATHER to Variants.WEATHER_BADGE -> "Badge"
    ElementType.WEATHER to Variants.WEATHER_COMPACT -> "One line"
    ElementType.WEATHER to Variants.WEATHER_LARGE -> "Large"
    ElementType.WEATHER to Variants.WEATHER_TEXT -> "Words"
    ElementType.NOW_PLAYING to Variants.NP_ROW -> "Row"
    ElementType.NOW_PLAYING to Variants.NP_PILL -> "Pill"
    ElementType.NOW_PLAYING to Variants.NP_MINI -> "Mini"
    ElementType.NOW_PLAYING to Variants.NP_LINE -> "Text line"
    ElementType.NOW_PLAYING to Variants.NP_BAR -> "Bottom bar"
    ElementType.NOW_PLAYING to Variants.NP_FULL -> "Big player"
    ElementType.HEADLINE to Variants.HEADLINE_PLAIN -> "Plain"
    ElementType.HEADLINE to Variants.HEADLINE_ITALIC -> "Italic"
    ElementType.NEXT_EVENT to Variants.NEXT_COMPACT -> "Compact"
    ElementType.NEXT_EVENT to Variants.NEXT_CARD -> "Card"
    ElementType.NEXT_EVENT to Variants.NEXT_LINE -> "One line"
    ElementType.TODAY_PANEL to Variants.PANEL_CARD -> "Card"
    ElementType.TODAY_PANEL to Variants.PANEL_COLUMN -> "Column"
    ElementType.TODAY_PANEL to Variants.PANEL_TERMINAL -> "Terminal"
    ElementType.SUMMARY to Variants.SUMMARY_LINES -> "Lines"
    ElementType.SUMMARY to Variants.SUMMARY_CHIP -> "Chip"
    else -> v
}

/** One line on what each element is, for the hidden list. */
private fun elementHint(type: ElementType): String = when (type) {
    ElementType.TIME -> "The time"
    ElementType.DATE -> "Today's date"
    ElementType.WEATHER -> "From Home Assistant (Home screen → Weather)"
    ElementType.NOW_PLAYING -> "The song playing, with play/pause"
    ElementType.HEADLINE -> "Odysseus's one-line summary of the day"
    ElementType.NEXT_EVENT -> "The next calendar event"
    ElementType.TODAY_PANEL -> "Today's events and to-dos from Odysseus"
    ElementType.SUMMARY -> "How many to-dos are left"
    ElementType.STATUS -> "Connection and do-not-disturb"
    ElementType.RECORD -> "Start or stop a recording"
    ElementType.UPDATE -> "Shows when an update is ready"
}

private fun update(change: (ClockStyle) -> ClockStyle) {
    HomeSettings.clockStyle = change(HomeSettings.clockStyle)
}

private fun updateTheme(change: (ClockTheme) -> ClockTheme) = update { it.copy(theme = change(it.theme)) }

/** A colour change also turns the app's own colours off, so the colour shows. */
private fun setColor(change: (ClockTheme) -> ClockTheme) = updateTheme { change(it).copy(useAppColors = false) }

/**
 * The whole Clock face page: the preview and the tabs beside it on a wide screen, above it on a
 * narrow one. [enabled] is false while the clock home screen is off (everything greys out).
 */
@Composable
fun ClockFaceSettings(enabled: Boolean, modifier: Modifier = Modifier, initialTab: ClockTab = ClockTab.LOOK) {
    val style = HomeSettings.clockStyle
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var withMusic by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 520.dp
        val paneWidth = (maxWidth * 0.42f).coerceAtMost(420.dp)
        val previewPane: @Composable ColumnScope.() -> Unit = {
            ClockLivePreview(style, withMusic)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Switch(checked = withMusic, onCheckedChange = { withMusic = it }, modifier = Modifier.padding(end = 8.dp))
                Text("Show with music playing", fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
            if (!enabled) {
                Text(
                    "The clock home screen is off. Turn it on in Home screen to use this face.",
                    fontSize = 13.sp, color = colors.error, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        val editor: @Composable ColumnScope.() -> Unit = {
            when (tab) {
                ClockTab.LOOK -> LookTab(style, enabled)
                ClockTab.COLOURS -> ColoursTab(style, enabled)
                ClockTab.FONTS -> FontsTab(style, enabled)
                ClockTab.LAYOUT -> LayoutTab(style, enabled)
                ClockTab.NIGHT -> NightTab(enabled)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(paneWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                ) {
                    previewPane()
                    Spacer(Modifier.height(8.dp))
                    TabChips(tab) { tab = it }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 8.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = editor,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.widthIn(max = 420.dp).align(Alignment.CenterHorizontally), content = previewPane)
                TabChips(tab) { tab = it }
                editor()
            }
        }
    }
}

/** The five tabs as chips: they fit under the preview even on the Echo Show 5. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabChips(selected: ClockTab, onPick: (ClockTab) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ClockTab.entries.forEach { t ->
            FilterChip(
                selected = t == selected, onClick = { onPick(t) },
                label = { Text(t.label, fontSize = 15.sp, fontWeight = if (t == selected) FontWeight.Bold else FontWeight.Medium) },
            )
        }
    }
}

// ---- Look -------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookTab(style: ClockStyle, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Heading("Designs", "Each is a whole look: colours, fonts and layout. Change any part afterwards in the other tabs.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ClockPresets.all.forEach { p ->
            val selected = style.preset == p.preset
            PresetCard(p, selected, changed = selected && !style.isPresetUnchanged, enabled = enabled) {
                HomeSettings.clockStyle = style.keepingSourceIn(p)
            }
        }
    }
    if (!style.isPresetUnchanged) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "You changed ${ClockPresets.label(style.preset)}.", fontSize = 14.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(enabled = enabled, onClick = { ClockPresets.byId(style.preset)?.let { HomeSettings.clockStyle = style.keepingSourceIn(it) } }) {
                Text("Undo my changes")
            }
        }
    }
    Heading("Dim the clock", "Makes the whole face fainter, for a room that is never quite dark.")
    ChipRow(DIMS, style.theme.dim, enabled) { d -> updateTheme { it.copy(dim = d) } }
}

/** A small picture of a preset: its background, the time in its font and colours, a date line. */
@Composable
private fun PresetCard(preset: ClockStyle, selected: Boolean, changed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val t = preset.theme
    val bg = if (t.useAppColors) colors.background else clockColor(t.background, colors.background)
    val grad = if (t.useAppColors) null else ClockColor.parse(t.gradient)?.let { Color(it.toInt()) }
    val text = if (t.useAppColors) colors.onBackground else clockColor(t.text, colors.onBackground)
    val muted = if (t.useAppColors) colors.onSurfaceVariant else clockColor(t.muted, colors.onSurfaceVariant)
    val accent = if (t.useAppColors) colors.primary else clockColor(t.accent, colors.primary)
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(132.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = "Design ${ClockPresets.label(preset.preset)}" + if (selected) ", chosen" else "" }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(shape)
                .then(if (grad != null) Modifier.background(Brush.linearGradient(listOf(bg, grad))) else Modifier.background(bg))
                .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape),
        ) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "10:08", color = text, fontSize = 28.sp, maxLines = 1,
                    fontFamily = ClockFontFamilies.family(t.timeFont), fontWeight = FontWeight(t.timeWeight),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(accent, CircleShape))
                    Text(
                        " Friday", color = muted, fontSize = 10.sp, maxLines = 1,
                        fontFamily = ClockFontFamilies.family(t.textFont),
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check, contentDescription = null, tint = colors.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(colors.primary, CircleShape).padding(2.dp),
                )
            }
        }
        Text(
            ClockPresets.label(preset.preset) + if (changed) " (changed)" else "",
            fontSize = 13.sp, color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---- Colours ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColoursTab(style: ClockStyle, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val theme = style.theme
    var fineTune by rememberSaveable { mutableStateOf(false) }
    val current = ClockPalettes.matching(theme)

    Heading("Colour scheme", "Colours picked to go together. Fonts and layout stay as they are.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SchemeChip("App colours", listOf(colors.background, colors.onBackground, colors.primary), theme.useAppColors, enabled) {
            updateTheme { it.copy(useAppColors = true) }
        }
        ClockPalettes.all.forEach { p ->
            SchemeChip(
                p.label,
                listOfNotNull(p.background, p.gradient, p.text, p.accent).map { clockColor(it, Color.Transparent) },
                current == p, enabled,
            ) { updateTheme { p.applyTo(it) } }
        }
    }
    if (!theme.useAppColors && current == null) {
        Text("Your own colours.", fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
    ClockContrast.warnings(theme).forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
            Text(w, fontSize = 13.sp, color = colors.error, modifier = Modifier.padding(start = 6.dp))
        }
    }
    SwitchLine(theme.artworkBackground, "Cover art as the background while music plays", enabled) { v ->
        updateTheme { it.copy(artworkBackground = v) }
    }

    Expander("Fine-tune colours", fineTune) { fineTune = !fineTune }
    if (fineTune) {
        if (theme.useAppColors) {
            Text("Now: the app's own colours. Picking a colour below switches to your own.", fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        ColorRow("Background", theme.background, enabled) { c -> setColor { it.copy(background = c!!) } }
        ColorRow("Gradient to", theme.gradient, enabled, allowNone = true) { c -> setColor { it.copy(gradient = c) } }
        ColorRow("Text", theme.text, enabled) { c -> setColor { it.copy(text = c!!) } }
        ColorRow("Secondary text", theme.muted, enabled) { c -> setColor { it.copy(muted = c!!) } }
        ColorRow("Accent", theme.accent, enabled) { c -> setColor { it.copy(accent = c!!) } }
        ColorRow("Cards", theme.panel, enabled) { c -> setColor { it.copy(panel = c!!) } }
    }
}

/** A scheme as a chip: a few dots of its colours and its name. */
@Composable
private fun SchemeChip(label: String, dots: List<Color>, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) colors.primary.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(20.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = "Colour scheme $label" + if (selected) ", chosen" else "" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                dots.forEach { c -> Box(Modifier.size(18.dp).background(c, CircleShape).border(1.dp, colors.outline, CircleShape)) }
            }
            Text(label, fontSize = 14.sp, color = colors.onSurface, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ColorRow(label: String, current: String?, enabled: Boolean, allowNone: Boolean = false, onPick: (String?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Label(label)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (allowNone) {
                Swatch(null, current == null, enabled, "$label: none") { onPick(null) }
            }
            ClockColor.PALETTE.forEach { hex ->
                Swatch(hex, current.equals(hex, ignoreCase = true), enabled, "$label $hex") { onPick(hex) }
            }
        }
        if (current != null && ClockColor.PALETTE.none { it.equals(current, ignoreCase = true) }) {
            Text("Now: $current", fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Swatch(hex: String?, selected: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outline, CircleShape)
            .padding(4.dp)
            .background(hex?.let { clockColor(it, Color.Transparent) } ?: Color.Transparent, CircleShape)
            .semantics { contentDescription = description }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (hex == null) Text("–", color = colors.onSurfaceVariant, fontSize = 14.sp)
    }
}

// ---- Fonts ------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontsTab(style: ClockStyle, enabled: Boolean) {
    val theme = style.theme
    var fineTune by rememberSaveable { mutableStateOf(false) }
    val current = ClockFontPairs.matching(theme)

    Heading("Font pairing", "A font for the time and one for everything else, picked to go together.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ClockFontPairs.all.forEach { f -> FontPairCard(f, current == f, enabled) { updateTheme { f.applyTo(it) } } }
    }
    if (current == null) Text("Your own fonts.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Expander("Fine-tune fonts", fineTune) { fineTune = !fineTune }
    if (fineTune) {
        Label("Time font")
        FontChips(theme.timeFont, enabled) { id -> updateTheme { it.copy(timeFont = id) } }
        Label("Time weight")
        ChipRow(WEIGHTS, theme.timeWeight, enabled) { w -> updateTheme { it.copy(timeWeight = w) } }
        Label("Space between digits")
        ChipRow(ClockFontPairs.SPACINGS, theme.timeSpacing, enabled) { s -> updateTheme { it.copy(timeSpacing = s) } }
        Label("Text font")
        FontChips(theme.textFont, enabled) { id -> updateTheme { it.copy(textFont = id) } }
    }
}

@Composable
private fun FontPairCard(pair: ClockFontPair, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(112.dp)
            .height(100.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant.copy(alpha = 0.4f))
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = "Font pairing ${pair.label}" + if (selected) ", chosen" else "" }
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        Text(
            "10:08", fontSize = 26.sp, color = colors.onSurface, maxLines = 1,
            fontFamily = ClockFontFamilies.family(pair.timeFont), fontWeight = FontWeight(pair.timeWeight),
        )
        Text("Friday", fontSize = 12.sp, color = colors.onSurfaceVariant, fontFamily = ClockFontFamilies.family(pair.textFont))
        Text(pair.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onSurface, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FontChips(selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockFonts.ALL.forEach { id ->
            FilterChip(
                selected = selected == id, enabled = enabled, onClick = { onPick(id) },
                label = { Text(ClockFonts.label(id), fontFamily = ClockFontFamilies.family(id), fontSize = 16.sp) },
            )
        }
    }
}

// ---- Layout -----------------------------------------------------------------------------------

@Composable
private fun LayoutTab(style: ClockStyle, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf<ElementType?>(ElementType.TIME) }
    Heading("On the clock", "Listed the way they sit on the screen, top to bottom. Tap one to move, resize or restyle it.")
    style.shownInReadingOrder().forEach { el ->
        ShownElement(style, el, open == el.type, enabled, onToggle = { open = if (open == el.type) null else el.type })
    }
    val hidden = style.elements.filter { !it.visible }
    if (hidden.isNotEmpty()) {
        Heading("Not shown", "Tap + to add one; it comes back where it was.")
        hidden.forEach { el ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).padding(vertical = 2.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(el.type.label, fontSize = 15.sp, color = colors.onSurface)
                    Text(elementHint(el.type), fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
                IconButton(enabled = enabled, onClick = { update { it.withElement(el.copy(visible = true)) }; open = el.type }) {
                    Icon(Icons.Default.Add, contentDescription = "Show ${el.type.label}", tint = colors.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ShownElement(style: ClockStyle, el: ClockElement, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant.copy(alpha = if (expanded) 0.7f else 0.4f))
            .then(if (expanded) Modifier.border(1.dp, colors.primary.copy(alpha = 0.6f), RoundedCornerShape(12.dp)) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(el.type.label, fontSize = 16.sp, color = colors.onSurface, fontWeight = FontWeight.Medium)
                Text(el.summary(::variantLabel), fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            IconButton(enabled = enabled, onClick = { update { it.withElement(el.copy(visible = false)) } }) {
                Icon(Icons.Default.VisibilityOff, contentDescription = "Hide ${el.type.label}", tint = colors.onSurfaceVariant)
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Close" else "Edit", tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (!expanded) return@Column
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column {
                    Label("Where")
                    AnchorPicker(el.anchor, enabled, el.type.label) { a -> update { it.withElement(el.copy(anchor = a)) } }
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(el.anchor.label)
                    val (place, count) = style.stackPlace(el.type)
                    if (count > 1) {
                        Text("$place of $count here", fontSize = 13.sp, color = colors.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(enabled = enabled && place > 1, onClick = { update { it.movedUp(el.type) } }) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp)); Text(" Up")
                            }
                            OutlinedButton(enabled = enabled && place < count, onClick = { update { it.movedDown(el.type) } }) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp)); Text(" Down")
                            }
                        }
                    } else {
                        Text("Alone here", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (el.type.hasSize) {
                Label("Size")
                ChipRow(ELEMENT_SIZES, el.scale, enabled) { s -> update { it.withElement(el.copy(scale = s)) } }
            }
            if (el.type.variants.size > 1) {
                Label("Look")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    el.type.variants.forEach { v ->
                        FilterChip(
                            selected = el.style == v, enabled = enabled,
                            onClick = { update { it.withElement(el.copy(variant = v)) } },
                            label = { Text(variantLabel(el.type, v)) },
                        )
                    }
                }
            }
            if (el.type == ElementType.NOW_PLAYING) NowPlayingSourcePicker(style, enabled)
        }
    }
}

/** "Music from": this device's player or the Music page's room (a device choice kept across designs). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingSourcePicker(style: ClockStyle, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Label("Music from")
    ChipRow(NowPlayingSource.entries.map { it to it.label }, style.nowPlayingFrom, enabled) { src -> update { it.copy(nowPlayingFrom = src) } }
    Text(
        if (style.nowPlayingFrom == NowPlayingSource.ROOM) {
            "The room chosen on the Music page (Music Assistant through Home Assistant), e.g. the kitchen's music on a tablet that plays none itself."
        } else {
            "What this device plays itself (Sendspin)."
        },
        fontSize = 12.sp, color = colors.onSurfaceVariant,
    )
    if (style.nowPlayingFrom == NowPlayingSource.ROOM && !MusicPage.available) {
        Text("Needs the Music page and Home Assistant", fontSize = 12.sp, color = colors.error)
    }
}

/** Nine small squares laid out like the screen; the chosen one filled. */
@Composable
private fun AnchorPicker(selected: Anchor, enabled: Boolean, name: String, onPick: (Anchor) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.border(1.dp, colors.outline, RoundedCornerShape(6.dp)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0..2) {
                    val a = Anchor.at(row, col)
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 30.dp)
                            .background(if (a == selected) colors.primary else colors.onSurface.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .semantics { contentDescription = "$name at ${a.label}" }
                            .then(if (enabled) Modifier.clickable { onPick(a) } else Modifier),
                    )
                }
            }
        }
    }
}

// ---- Night ------------------------------------------------------------------------------------

/** The automatic night look (NightMode): on/off, how dark, which design, dimming. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NightTab(enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val on = HomeSettings.nightAuto
    Heading("Night look", "From the light sensor: after a minute of dark the clock changes; about 10 s after the light comes back it changes back.")
    SwitchLine(on, "Switch to a night look when the room is dark", enabled) { HomeSettings.nightAuto = it }
    // A live reading, to choose the level: read while this menu is open.
    NightMode.LightReadings()
    val lux = NightMode.lux
    Text(
        (if (lux >= 0f) "Light here now: ${"%.0f".format(lux)} lux" else "Light here now: no reading yet") +
            if (on && NightMode.active) " · night look on" else "",
        fontSize = 13.sp, color = colors.onSurfaceVariant,
    )
    if (!on) return
    Label("Dark means")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NightRules.DARK_CHOICES.forEach { l ->
            FilterChip(
                selected = HomeSettings.nightDarkLux == l, enabled = enabled,
                onClick = { HomeSettings.nightDarkLux = l },
                label = { Text("${NightRules.darkLabel(l)} (${l.toInt()} lux)") },
            )
        }
    }
    Label("Night design")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ClockPresets.all.forEach { p ->
            PresetCard(p, HomeSettings.nightPreset == p.preset, changed = false, enabled = enabled) { HomeSettings.nightPreset = p.preset }
        }
    }
    SwitchLine(HomeSettings.nightDim, "Lowest screen brightness at night", enabled) { HomeSettings.nightDim = it }
}

// ---- Small shared pieces ----------------------------------------------------------------------

@Composable
private fun Heading(title: String, detail: String? = null) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.primary)
        if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SwitchLine(checked: Boolean, text: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 14.dp))
    }
}

/** "Fine-tune …": a row that opens or closes the detailed controls under it. */
@Composable
private fun Expander(text: String, open: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        Text(" $text")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(choices: List<Pair<T, String>>, selected: T, enabled: Boolean, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (v, label) ->
            FilterChip(selected = selected == v, enabled = enabled, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}
