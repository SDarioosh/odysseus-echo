package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: the clock face's design system, as plain data (no Android, no Compose) so it is
// unit-tested (ClockDesignTest). Instead of six separate colour pickers and three font pickers,
// the menu offers whole colour schemes (ClockPalettes) and font pairings (ClockFontPairs) that
// are known to work together; each can still be fine-tuned. ClockContrast warns when the chosen
// colours are hard to read, and the Anchor/stack helpers give the Layout tab plain words
// ("Top left, 2nd of 3").

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A colour scheme: every colour of a ClockTheme, picked to go together. */
data class ClockPalette(
    val id: String,
    val label: String,
    val background: String,
    val gradient: String? = null,
    val text: String,
    val muted: String,
    val accent: String,
    val panel: String,
    val alert: String,
    val highlight: String? = null,
    /** Deliberately dim, for a dark bedroom: not held to the daytime contrast rules. */
    val night: Boolean = false,
) {
    /** [theme] in these colours (and no longer the app's own). Fonts and the rest stay. */
    fun applyTo(theme: ClockTheme): ClockTheme = theme.copy(
        useAppColors = false, background = background, gradient = gradient, text = text, muted = muted,
        accent = accent, panel = panel, alert = alert, highlight = highlight,
    )

    /** [theme] uses exactly these colours. */
    fun matches(theme: ClockTheme): Boolean = !theme.useAppColors &&
        same(theme.background, background) && same(theme.gradient, gradient) && same(theme.text, text) &&
        same(theme.muted, muted) && same(theme.accent, accent) && same(theme.panel, panel)

    private fun same(a: String?, b: String?) = (a == null && b == null) || (a != null && b != null && a.equals(b, ignoreCase = true))
}

object ClockPalettes {
    // The presets' own colours first, so a preset's scheme can be put on another layout.
    val all: List<ClockPalette> = listOf(
        ClockPalette("one_dark", "One Dark", "#282C34", null, "#9CDEF2", "#739FAF", "#E06C75", "#1E2228", "#FF6666"),
        ClockPalette("midnight", "Midnight", "#0B0D12", null, "#E8ECF3", "#8A93A6", "#7AA2F7", "#1A1E27", "#F87171"),
        ClockPalette("paper", "Paper", "#F3EEE4", null, "#2B2620", "#6B6157", "#B4532A", "#E9E2D4", "#B4532A"),
        ClockPalette("sunset", "Sunset", "#1D1238", "#E07A5F", "#FFFFFF", "#E6DDF0", "#FFD6A5", "#33FFFFFF", "#FFD6A5"),
        ClockPalette("terminal", "Terminal", "#0C0F0A", null, "#C5D1B5", "#6B7A5C", "#9ECE6A", "#1A2014", "#F7768E", "#E0AF68"),
        ClockPalette("amber", "Amber", "#101418", null, "#F3F4F6", "#9AA4B2", "#FFB547", "#171D24", "#F87171"),
        ClockPalette("graphite", "Graphite", "#16161C", null, "#FFFFFF", "#D0D0D8", "#FFFFFF", "#33FFFFFF", "#FF8A80"),
        ClockPalette("ocean", "Ocean", "#0B1D2A", "#123B52", "#E0F2FE", "#8FB5CC", "#38BDF8", "#14303F", "#FB7185"),
        ClockPalette("forest", "Forest", "#0F1A14", null, "#E3F0E6", "#8FAA98", "#6FCF97", "#16241C", "#F2994A"),
        ClockPalette("rose", "Rose", "#FFF1F2", null, "#3F1D25", "#7D5560", "#E11D48", "#FFE4E6", "#BE123C"),
        ClockPalette("mono_dark", "Black & white", "#000000", null, "#FFFFFF", "#A3A3A3", "#FFFFFF", "#161616", "#F87171"),
        ClockPalette("mono_light", "White & black", "#FFFFFF", null, "#111111", "#5F5F5F", "#111111", "#F0F0F0", "#B3261E"),
        ClockPalette("nightstand", "Night red", "#000000", null, "#B3261E", "#741914", "#B3261E", "#140303", "#B3261E", night = true),
    )

    fun byId(id: String): ClockPalette? = all.firstOrNull { it.id == id }

    /** The scheme [theme] uses now; null = the app's own colours or a mix of the user's own. */
    fun matching(theme: ClockTheme): ClockPalette? = all.firstOrNull { it.matches(theme) }
}

/** Two fonts and a weight for the time, picked to go together. */
data class ClockFontPair(
    val id: String,
    val label: String,
    val timeFont: String,
    val timeWeight: Int,
    val timeSpacing: Float,
    val textFont: String,
) {
    fun applyTo(theme: ClockTheme): ClockTheme =
        theme.copy(timeFont = timeFont, timeWeight = timeWeight, timeSpacing = timeSpacing, textFont = textFont)

    fun matches(theme: ClockTheme): Boolean =
        theme.timeFont == timeFont && theme.timeWeight == timeWeight && theme.timeSpacing == timeSpacing && theme.textFont == textFont
}

object ClockFontPairs {
    val all: List<ClockFontPair> = listOf(
        ClockFontPair("code", "Code", ClockFonts.FIRA, 300, 0f, ClockFonts.FIRA),
        ClockFontPair("modern", "Modern", ClockFonts.INTER, 200, -0.03f, ClockFonts.INTER),
        ClockFontPair("editorial", "Editorial", ClockFonts.PLAYFAIR, 400, -0.02f, ClockFonts.MANROPE),
        ClockFontPair("soft", "Soft", ClockFonts.OUTFIT, 200, -0.02f, ClockFonts.OUTFIT),
        ClockFontPair("poster", "Poster", ClockFonts.BEBAS, 400, 0.01f, ClockFonts.MANROPE),
        ClockFontPair("serif", "Serif", ClockFonts.DM_SERIF, 400, 0f, ClockFonts.INTER),
        ClockFontPair("rounded", "Friendly", ClockFonts.MANROPE, 300, -0.01f, ClockFonts.MANROPE),
    )

    fun matching(theme: ClockTheme): ClockFontPair? = all.firstOrNull { it.matches(theme) }

    /** Letter spacing choices for the time, in em. */
    val SPACINGS: List<Pair<Float, String>> = listOf(-0.03f to "Tight", 0f to "Normal", 0.03f to "Wide")
}

/** How readable the theme's text is on its background (WCAG contrast ratio, 1 to 21). */
object ClockContrast {
    /** Under this the time and main text are hard to read (WCAG's level for large text). */
    const val TEXT_MIN = 3.0
    /** Under this secondary text (date, artist) is hard to read. */
    const val MUTED_MIN = 1.8

    fun ratio(foreground: String?, background: String?): Double? {
        val fg = ClockColor.parse(foreground) ?: return null
        val bg = ClockColor.parse(background) ?: return null
        val a = luminance(fg)
        val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /**
     * The lowest ratio of [foreground] over the background: the background itself and, with a
     * gradient, the colour halfway along it (where most text sits).
     */
    fun worst(foreground: String?, theme: ClockTheme): Double? {
        val onBg = ratio(foreground, theme.background) ?: return null
        val mid = theme.gradient?.let { blend(theme.background, it) }?.let { ratio(foreground, it) }
        return if (mid != null) min(onBg, mid) else onBg
    }

    /** Plain-words warnings for the menu; empty when everything reads well (or the app's colours are used). */
    fun warnings(theme: ClockTheme): List<String> {
        if (theme.useAppColors) return emptyList()
        val out = mutableListOf<String>()
        worst(theme.text, theme)?.let { if (it < TEXT_MIN) out += "The time may be hard to read on this background." }
        worst(theme.muted, theme)?.let { if (it < MUTED_MIN) out += "Secondary text (date, artist) may be hard to read." }
        return out
    }

    /** Relative luminance of an ARGB colour (alpha ignored). */
    private fun luminance(argb: Long): Double {
        fun ch(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun blend(a: String, b: String): String? {
        val x = ClockColor.parse(a) ?: return null
        val y = ClockColor.parse(b) ?: return null
        fun mid(shift: Int) = ((((x shr shift) and 0xFF) + ((y shr shift) and 0xFF)) / 2)
        return "#%02X%02X%02X".format(mid(16), mid(8), mid(0))
    }
}

/** "Top left", "Middle", ... */
val Anchor.label: String
    get() {
        if (this == Anchor.CENTER) return "Middle"
        val v = when (row) { 0 -> "Top"; 1 -> "Middle"; else -> "Bottom" }
        val h = when (col) { 0 -> "left"; 1 -> "centre"; else -> "right" }
        return "$v $h"
    }

/** Where [type] stands among the visible elements at its anchor: (1-based place, how many). */
fun ClockStyle.stackPlace(type: ElementType): Pair<Int, Int> {
    val el = element(type)
    val stack = elements.filter { it.visible && it.anchor == el.anchor }
    val i = stack.indexOfFirst { it.type == type }
    return (i + 1) to stack.size
}

/** [type] swapped with the visible element after it at the same anchor (stack order). */
fun ClockStyle.movedDown(type: ElementType): ClockStyle {
    val list = elements
    val i = list.indexOfFirst { it.type == type }
    if (i < 0) return this
    val anchor = list[i].anchor
    val next = (i + 1 until list.size).firstOrNull { list[it].visible && list[it].anchor == anchor } ?: return this
    return movedUp(list[next].type)
}

/**
 * The visible elements in reading order: by anchor (top row first, left to right), then stack
 * order. The Layout tab lists them this way, so the list looks like the screen.
 */
fun ClockStyle.shownInReadingOrder(): List<ClockElement> =
    elements.withIndex()
        .filter { it.value.visible }
        .sortedWith(compareBy({ it.value.anchor.row }, { it.value.anchor.col }, { it.index }))
        .map { it.value }

/** A short summary of an element's settings for its collapsed row: "Top left · Large · Badge". */
fun ClockElement.summary(variantLabel: (ElementType, String) -> String): String {
    val parts = mutableListOf(anchor.label)
    if (type.hasSize) parts += sizeLabel(scale)
    if (type.variants.size > 1) parts += variantLabel(type, style)
    return parts.joinToString(" · ")
}

/** The status line, record button and update notice have one fixed size. */
val ElementType.hasSize: Boolean get() = this != ElementType.STATUS && this != ElementType.RECORD && this != ElementType.UPDATE

val ELEMENT_SIZES: List<Pair<Float, String>> = listOf(0.8f to "Small", 1f to "Medium", 1.25f to "Large", 1.5f to "Extra large")

/** The size's name; a size set elsewhere (a preset's 1.15) reads as the nearest one. */
fun sizeLabel(scale: Float): String = ELEMENT_SIZES.minBy { kotlin.math.abs(it.first - scale) }.second
