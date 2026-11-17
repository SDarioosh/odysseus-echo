package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: Menu → Odysseus → Home screen → Clock style. Picks a preset, then changes its
// fonts, colours and elements (show/hide, where, how big, which look). Every change is saved at
// once in HomeSettings.clockStyle and shows on the clock as soon as the menu closes.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.odysseus.home.HomeSettings
import com.msp1974.vacompanion.odysseus.home.music.MusicPage

private val SIZES = listOf(0.8f to "S", 1f to "M", 1.25f to "L", 1.5f to "XL")
private val WEIGHTS = listOf(200 to "Thin", 300 to "Light", 400 to "Regular", 500 to "Medium")
private val DIMS = listOf(1f to "Off", 0.8f to "80%", 0.6f to "60%", 0.4f to "40%")

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

private fun update(change: (ClockStyle) -> ClockStyle) {
    HomeSettings.clockStyle = change(HomeSettings.clockStyle)
}

private fun updateTheme(change: (ClockTheme) -> ClockTheme) = update { it.copy(theme = change(it.theme)) }

/** A colour change also turns the app's own colours off, so the colour shows. */
private fun setColor(change: (ClockTheme) -> ClockTheme) = updateTheme { change(it).copy(useAppColors = false) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClockStyleSettingsSection(enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val style = HomeSettings.clockStyle
    val theme = style.theme

    Text("Clock style", fontSize = 16.sp, color = colors.onSurface)
    Text(
        "Pick a look, then change its fonts, colours and what shows where. Close the menu to see the clock.",
        fontSize = 13.sp, color = colors.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockPresets.all.forEach { p ->
            val selected = style.preset == p.preset
            FilterChip(
                selected = selected, enabled = enabled,
                onClick = { HomeSettings.clockStyle = style.keepingSourceIn(p) },
                label = { Text(ClockPresets.label(p.preset) + if (selected && !style.isPresetUnchanged) " (changed)" else "") },
            )
        }
    }
    if (!style.isPresetUnchanged) {
        OutlinedButton(enabled = enabled, onClick = { ClockPresets.byId(style.preset)?.let { HomeSettings.clockStyle = style.keepingSourceIn(it) } }) {
            Text("Undo my changes to ${ClockPresets.label(style.preset)}")
        }
    }

    Label("Clock font")
    FontChips(theme.timeFont, enabled) { id -> updateTheme { it.copy(timeFont = id) } }
    Label("Clock weight")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WEIGHTS.forEach { (w, label) ->
            FilterChip(selected = theme.timeWeight == w, enabled = enabled, onClick = { updateTheme { it.copy(timeWeight = w) } }, label = { Text(label) })
        }
    }
    Label("Text font")
    FontChips(theme.textFont, enabled) { id -> updateTheme { it.copy(textFont = id) } }

    if (theme.useAppColors) {
        Text("Colours: the app's own. Pick any colour below to use your own.", fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
    ColorRow("Background", theme.background, enabled) { c -> setColor { it.copy(background = c!!) } }
    ColorRow("Gradient to", theme.gradient, enabled, allowNone = true) { c -> setColor { it.copy(gradient = c) } }
    ColorRow("Text", theme.text, enabled) { c -> setColor { it.copy(text = c!!) } }
    ColorRow("Secondary text", theme.muted, enabled) { c -> setColor { it.copy(muted = c!!) } }
    ColorRow("Accent", theme.accent, enabled) { c -> setColor { it.copy(accent = c!!) } }
    ColorRow("Cards", theme.panel, enabled) { c -> setColor { it.copy(panel = c!!) } }

    Label("Now playing from")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NowPlayingSource.entries.forEach { src ->
            FilterChip(
                selected = style.nowPlayingFrom == src, enabled = enabled,
                onClick = { update { it.copy(nowPlayingFrom = src) } }, label = { Text(src.label) },
            )
        }
    }
    Text(
        if (style.nowPlayingFrom == NowPlayingSource.ROOM) {
            "The room chosen on the Music page (Music Assistant through Home Assistant), e.g. the kitchen's music on a tablet that plays none itself."
        } else {
            "What this device plays itself (Sendspin)."
        },
        fontSize = 13.sp, color = colors.onSurfaceVariant,
    )
    if (style.nowPlayingFrom == NowPlayingSource.ROOM && !MusicPage.available) {
        Text("Needs the Music page and Home Assistant", fontSize = 13.sp, color = colors.error)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = theme.artworkBackground, enabled = enabled, onCheckedChange = { v -> updateTheme { it.copy(artworkBackground = v) } })
        Text("Cover art as the background while music plays", fontSize = 15.sp, color = colors.onSurface, modifier = Modifier.padding(start = 14.dp))
    }
    Label("Dim the clock")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DIMS.forEach { (d, label) ->
            FilterChip(selected = theme.dim == d, enabled = enabled, onClick = { updateTheme { it.copy(dim = d) } }, label = { Text(label) })
        }
    }

    NightSettings(enabled)

    Text("Elements", fontSize = 16.sp, color = colors.onSurface, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Turn each one on or off and pick where it goes. Elements in the same place stack; ↑ moves one above the one before it.",
        fontSize = 13.sp, color = colors.onSurfaceVariant,
    )
    style.elements.forEach { el -> ElementSettings(style, el, enabled) }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
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
            .size(34.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outline, CircleShape)
            .padding(4.dp)
            .background(hex?.let { clockColor(it, Color.Transparent) } ?: Color.Transparent, CircleShape)
            .semantics { contentDescription = description }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (hex == null) Text("–", color = colors.onSurfaceVariant, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ElementSettings(style: ClockStyle, el: ClockElement, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = el.visible, enabled = enabled, onCheckedChange = { v -> update { it.withElement(el.copy(visible = v)) } })
            Text(el.type.label, fontSize = 16.sp, color = colors.onSurface, modifier = Modifier.padding(start = 14.dp).weight(1f))
            if (el.visible) {
                OutlinedButton(enabled = enabled && canMoveUp(style, el), onClick = { update { it.movedUp(el.type) } }) { Text("↑") }
            }
        }
        if (!el.visible) return@Column
        Row(verticalAlignment = Alignment.Top) {
            AnchorPicker(el.anchor, enabled, el.type.label) { a -> update { it.withElement(el.copy(anchor = a)) } }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (el.type != ElementType.STATUS && el.type != ElementType.RECORD && el.type != ElementType.UPDATE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SIZES.forEach { (s, label) ->
                            FilterChip(selected = el.scale == s, enabled = enabled, onClick = { update { it.withElement(el.copy(scale = s)) } }, label = { Text(label) })
                        }
                    }
                }
                if (el.type.variants.size > 1) {
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
            }
        }
    }
}

/** Nine small squares, one per anchor; the chosen one filled. */
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
                            .size(width = 40.dp, height = 26.dp)
                            .background(if (a == selected) colors.primary else colors.onSurface.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .semantics { contentDescription = "$name at ${a.name.lowercase().replace('_', ' ')}" }
                            .then(if (enabled) Modifier.clickable { onPick(a) } else Modifier),
                    )
                }
            }
        }
    }
}

private fun canMoveUp(style: ClockStyle, el: ClockElement): Boolean =
    style.elements.takeWhile { it.type != el.type }.any { it.visible && it.anchor == el.anchor }

/** The automatic night look (NightMode): on/off, how dark, which preset, dimming. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NightSettings(enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val on = HomeSettings.nightAuto
    Text("Night look", fontSize = 16.sp, color = colors.onSurface, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = on, enabled = enabled, onCheckedChange = { HomeSettings.nightAuto = it })
        Column(Modifier.padding(start = 14.dp)) {
            Text("Switch to a night look when the room is dark", fontSize = 15.sp, color = colors.onSurface)
            Text(
                "From the light sensor: after a minute of dark it changes, about 10 s after the light comes back it changes back.",
                fontSize = 13.sp, color = colors.onSurfaceVariant,
            )
        }
    }
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
    Label("Night style")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockPresets.all.forEach { p ->
            FilterChip(
                selected = HomeSettings.nightPreset == p.preset, enabled = enabled,
                onClick = { HomeSettings.nightPreset = p.preset },
                label = { Text(ClockPresets.label(p.preset)) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = HomeSettings.nightDim, enabled = enabled, onCheckedChange = { HomeSettings.nightDim = it })
        Text("Lowest screen brightness at night", fontSize = 15.sp, color = colors.onSurface, modifier = Modifier.padding(start = 14.dp))
    }
}
