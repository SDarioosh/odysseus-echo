package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: how the clock page looks, as plain data (no Android, no Compose) so it is
// unit-tested (ClockStyleTest) and stored as JSON in HomeSettings.
//
// A style is a theme (colours, fonts) plus one entry per element (time, date, weather, now
// playing, Odysseus's Today data, ...). Each element has an anchor, one of nine points on the
// screen; elements sharing an anchor stack top to bottom in list order. Hidden elements stay in
// the list, so turning one on brings it back where it was. Presets are complete styles; picking
// one replaces the current style, and any change after that is kept as the user's own.

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class Anchor(val row: Int, val col: Int) {
    TOP_START(0, 0), TOP_CENTER(0, 1), TOP_END(0, 2),
    CENTER_START(1, 0), CENTER(1, 1), CENTER_END(1, 2),
    BOTTOM_START(2, 0), BOTTOM_CENTER(2, 1), BOTTOM_END(2, 2);

    companion object {
        fun at(row: Int, col: Int): Anchor = entries.first { it.row == row && it.col == col }
    }
}

/** What can be on the clock page, with the styles ("variants") each one offers; the first is the default. */
@Serializable
enum class ElementType(val label: String, val variants: List<String>) {
    TIME("Time", listOf(Variants.TIME_PLAIN, Variants.TIME_WEEKDAY)),
    DATE("Date", listOf(Variants.DATE_LONG, Variants.DATE_CAPS, Variants.DATE_WEEKDAY, Variants.DATE_MONTH_DAY, Variants.DATE_ELEGANT, Variants.DATE_ISO)),
    WEATHER("Weather", listOf(Variants.WEATHER_BADGE, Variants.WEATHER_COMPACT, Variants.WEATHER_LARGE, Variants.WEATHER_TEXT)),
    NOW_PLAYING("Now playing", listOf(Variants.NP_ROW, Variants.NP_PILL, Variants.NP_MINI, Variants.NP_LINE, Variants.NP_BAR, Variants.NP_FULL)),
    HEADLINE("Today's headline", listOf(Variants.HEADLINE_PLAIN, Variants.HEADLINE_ITALIC)),
    NEXT_EVENT("Next event", listOf(Variants.NEXT_COMPACT, Variants.NEXT_CARD, Variants.NEXT_LINE)),
    TODAY_PANEL("Today panel", listOf(Variants.PANEL_CARD, Variants.PANEL_COLUMN, Variants.PANEL_TERMINAL)),
    SUMMARY("To-do summary", listOf(Variants.SUMMARY_LINES, Variants.SUMMARY_CHIP)),
    STATUS("Status line", listOf("plain")),
    RECORD("Record button", listOf("plain")),
    UPDATE("Update notice", listOf("plain")),
}

object Variants {
    const val TIME_PLAIN = "plain"
    const val TIME_WEEKDAY = "weekday"     // the weekday small above the time

    const val DATE_LONG = "long"           // Friday, November 6
    const val DATE_CAPS = "caps"           // FRIDAY · NOVEMBER 6
    const val DATE_WEEKDAY = "weekday"     // FRIDAY
    const val DATE_MONTH_DAY = "month_day" // November 6
    const val DATE_ELEGANT = "elegant"     // November 6, in the time's font, italic
    const val DATE_ISO = "iso"             // Fri 2026-11-06

    const val WEATHER_BADGE = "badge"      // icon, temperature, high/low under it
    const val WEATHER_COMPACT = "compact"  // one line: icon 83° H 83 · L 75
    const val WEATHER_LARGE = "large"      // big icon over a big temperature
    const val WEATHER_TEXT = "text"        // 83° rainy · high 83, low 75

    const val NP_ROW = "row"     // artwork, title/artist, previous/play/next (the original)
    const val NP_PILL = "pill"   // rounded pill: small round artwork, title/artist, play/pause
    const val NP_MINI = "mini"   // artwork, title/artist, small transport
    const val NP_LINE = "line"   // one line of text and play/pause
    const val NP_BAR = "bar"     // a status bar along the bottom edge
    const val NP_FULL = "full"   // large artwork and transport

    const val HEADLINE_PLAIN = "plain"
    const val HEADLINE_ITALIC = "italic"

    const val NEXT_COMPACT = "compact" // IN 29 MIN / Dentist · 3:30
    const val NEXT_CARD = "card"       // frosted card with the event after it
    const val NEXT_LINE = "line"       // 3:30 Dentist (in 29 min)

    const val PANEL_CARD = "card"
    const val PANEL_COLUMN = "column"
    const val PANEL_TERMINAL = "terminal"

    const val SUMMARY_LINES = "lines"
    const val SUMMARY_CHIP = "chip"
}

@Serializable
data class ClockElement(
    val type: ElementType,
    val anchor: Anchor = Anchor.CENTER,
    val visible: Boolean = true,
    /** Size multiplier (the time's base size comes from the screen). */
    val scale: Float = 1f,
    val variant: String = "",
    /** Nudge from the anchor, in dp. */
    val dx: Int = 0,
    val dy: Int = 0,
    /** Share of the screen width this element takes (0 = as wide as its content). */
    val width: Float = 0f,
    /** Take the full height of the screen (panels). */
    val fillHeight: Boolean = false,
) {
    /** The variant to draw: the stored one if this type offers it, else the type's default. */
    val style: String get() = variant.takeIf { it in type.variants } ?: type.variants.first()
}

@Serializable
data class ClockTheme(
    /** Use the app's own colours (dark/light) instead of the ones below: the original look. */
    val useAppColors: Boolean = false,
    val background: String = "#282C34",
    /** Second background colour for a diagonal gradient; null = solid. */
    val gradient: String? = null,
    val text: String = "#9CDEF2",
    val muted: String = "#739FAF",
    val accent: String = "#E06C75",
    /** Cards and panels. */
    val panel: String = "#1E2228",
    val alert: String = "#FF6666",
    /** A second accent for dates and headlines; null = the accent. */
    val highlight: String? = null,
    val timeFont: String = ClockFonts.FIRA,
    /** 200 (thin) to 600; the nearest weight the font has is used. */
    val timeWeight: Int = 300,
    /** Extra space between the time's digits, in em (negative = tighter). */
    val timeSpacing: Float = 0f,
    val textFont: String = ClockFonts.FIRA,
    /** AM/PM in the accent colour (else the secondary text colour). */
    val amPmAccent: Boolean = false,
    /** While music plays, the blurred cover art is the background. */
    val artworkBackground: Boolean = false,
    /** Command-line prompts before the time and the Today data. */
    val terminal: Boolean = false,
    /** Whole-page opacity for a dim night look (1 = normal). */
    val dim: Float = 1f,
)

/**
 * Where the clock's now-playing element (and the cover-art background) takes its music from:
 * this device's own Sendspin player, or the Music page's selected room (MusicRepository). Stored
 * as "device" / "room"; anything else reads as [DEVICE].
 */
@Serializable
enum class NowPlayingSource(val label: String) {
    @SerialName("device") DEVICE("This device"),
    @SerialName("room") ROOM("Selected room"),
}

@Serializable
data class ClockStyle(
    /** The preset this style started from. */
    val preset: String = ClockPresets.CLASSIC,
    val theme: ClockTheme = ClockTheme(useAppColors = true),
    val elements: List<ClockElement> = emptyList(),
    /** "Now playing from" (a device choice more than a look: kept when a preset is picked). */
    val nowPlayingFrom: NowPlayingSource = NowPlayingSource.DEVICE,
) {
    fun element(type: ElementType): ClockElement = elements.firstOrNull { it.type == type } ?: ClockElement(type, visible = false)

    fun withElement(updated: ClockElement): ClockStyle =
        copy(elements = elements.map { if (it.type == updated.type) updated else it })

    /** [type] swapped with the visible element before it at the same anchor (stack order). */
    fun movedUp(type: ElementType): ClockStyle {
        val list = elements.toMutableList()
        val i = list.indexOfFirst { it.type == type }
        if (i <= 0) return this
        val anchor = list[i].anchor
        val j = (i - 1 downTo 0).firstOrNull { list[it].visible && list[it].anchor == anchor } ?: return this
        list.add(j, list.removeAt(i))
        return copy(elements = list)
    }

    /** Same as its preset (nothing changed since it was picked). "Now playing from" doesn't count. */
    val isPresetUnchanged: Boolean
        get() = ClockPresets.byId(preset)?.let { normalized(it).copy(nowPlayingFrom = nowPlayingFrom) == this } ?: false

    /** [preset] (picked in the settings, or the night look) with this style's "Now playing from" kept. */
    fun keepingSourceIn(preset: ClockStyle): ClockStyle = preset.copy(nowPlayingFrom = nowPlayingFrom)

    companion object {
        /** Every element type exactly once (first entry wins); missing ones are added hidden. */
        fun normalized(style: ClockStyle): ClockStyle {
            val seen = LinkedHashMap<ElementType, ClockElement>()
            style.elements.forEach { if (it.type !in seen) seen[it.type] = it }
            ElementType.entries.forEach { if (it !in seen) seen[it] = ClockElement(it, defaultAnchor(it), visible = false) }
            val theme = style.theme.copy(
                timeWeight = style.theme.timeWeight.coerceIn(100, 900),
                dim = style.theme.dim.coerceIn(0.2f, 1f),
                timeFont = style.theme.timeFont.takeIf { it in ClockFonts.ALL } ?: ClockFonts.FIRA,
                textFont = style.theme.textFont.takeIf { it in ClockFonts.ALL } ?: ClockFonts.FIRA,
            )
            return style.copy(
                theme = theme,
                elements = seen.values.map { it.copy(scale = it.scale.coerceIn(0.5f, 2.5f), width = it.width.coerceIn(0f, 1f)) },
            )
        }

        private fun defaultAnchor(type: ElementType) = when (type) {
            ElementType.WEATHER -> Anchor.TOP_START
            ElementType.NEXT_EVENT, ElementType.UPDATE -> Anchor.TOP_END
            ElementType.STATUS -> Anchor.BOTTOM_CENTER
            ElementType.RECORD, ElementType.NOW_PLAYING -> Anchor.BOTTOM_END
            ElementType.TODAY_PANEL, ElementType.SUMMARY -> Anchor.CENTER_END
            else -> Anchor.CENTER
        }
    }
}

object ClockFonts {
    const val FIRA = "fira"
    const val INTER = "inter"
    const val MANROPE = "manrope"
    const val OUTFIT = "outfit"
    const val PLAYFAIR = "playfair"
    const val DM_SERIF = "dmserif"
    const val BEBAS = "bebas"

    val ALL = listOf(FIRA, INTER, MANROPE, OUTFIT, PLAYFAIR, DM_SERIF, BEBAS)

    fun label(id: String) = when (id) {
        FIRA -> "Fira Code"
        INTER -> "Inter"
        MANROPE -> "Manrope"
        OUTFIT -> "Outfit"
        PLAYFAIR -> "Playfair"
        DM_SERIF -> "DM Serif"
        BEBAS -> "Bebas Neue"
        else -> id
    }
}

/** "#RRGGBB" or "#AARRGGBB" to an ARGB value; null when it isn't one. */
object ClockColor {
    fun parse(hex: String?): Long? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (!h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (h.length) {
            6 -> 0xFF000000L or h.toLong(16)
            8 -> h.toLong(16)
            else -> null
        }
    }

    /** The swatches offered in the settings. */
    val PALETTE = listOf(
        "#000000", "#0B0D12", "#101418", "#1E2228", "#282C34", "#1D1238", "#4B2463", "#0C0F0A",
        "#2B2620", "#F3EEE4", "#FFFFFF", "#E8ECF3", "#9AA4B2", "#8A93A6", "#6B6157", "#9CDEF2",
        "#7AA2F7", "#9ECE6A", "#E0AF68", "#FFB547", "#F0A35E", "#C8566A", "#E06C75", "#F87171",
        "#B4532A", "#B3261E", "#C678DD",
    )
}

object ClockStyleCodec {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    fun encode(style: ClockStyle): String = json.encodeToString(style)

    /** The stored style, or the Classic preset when there is none or it can't be read. */
    fun decode(text: String?): ClockStyle {
        if (text.isNullOrBlank()) return ClockStyle.normalized(ClockPresets.classic)
        val parsed = runCatching { json.decodeFromString<ClockStyle>(text) }.getOrNull()
            ?: return ClockStyle.normalized(ClockPresets.classic)
        return ClockStyle.normalized(parsed)
    }
}
