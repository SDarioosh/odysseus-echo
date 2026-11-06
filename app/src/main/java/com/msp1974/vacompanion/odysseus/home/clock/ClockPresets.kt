package com.msp1974.vacompanion.odysseus.home.clock

// Odysseus fork: the clock page's built-in looks. Each is a complete ClockStyle; "Classic" is the
// clock as it was before styles existed and stays the default. Tuned on the Echo Show 5
// (960×480 px, about 790×395 dp); the time's size scales from the screen, so they hold on other
// sizes too.

import com.msp1974.vacompanion.odysseus.home.clock.Anchor.BOTTOM_CENTER
import com.msp1974.vacompanion.odysseus.home.clock.Anchor.BOTTOM_END
import com.msp1974.vacompanion.odysseus.home.clock.Anchor.BOTTOM_START
import com.msp1974.vacompanion.odysseus.home.clock.Anchor.CENTER
import com.msp1974.vacompanion.odysseus.home.clock.Anchor.CENTER_END
import com.msp1974.vacompanion.odysseus.home.clock.Anchor.CENTER_START
import com.msp1974.vacompanion.odysseus.home.clock.Anchor.TOP_END
import com.msp1974.vacompanion.odysseus.home.clock.Anchor.TOP_START
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.DATE
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.HEADLINE
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.NEXT_EVENT
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.NOW_PLAYING
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.RECORD
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.STATUS
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.SUMMARY
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.TIME
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.TODAY_PANEL
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.UPDATE
import com.msp1974.vacompanion.odysseus.home.clock.ElementType.WEATHER

object ClockPresets {
    const val CLASSIC = "classic"
    const val MIDNIGHT = "midnight"
    const val PAPER = "paper"
    const val SUNSET = "sunset"
    const val TERMINAL = "terminal"
    const val BOLD = "bold"
    const val ALBUM = "album"
    const val NIGHTSTAND = "nightstand"

    private fun e(
        type: ElementType, anchor: Anchor, variant: String = "", scale: Float = 1f,
        width: Float = 0f, fillHeight: Boolean = false, dx: Int = 0, dy: Int = 0,
    ) = ClockElement(type, anchor, true, scale, variant, dx, dy, width, fillHeight)

    /** The original clock: the app's colours and Fira Code, time and date in the middle. */
    val classic = ClockStyle(
        preset = CLASSIC,
        theme = ClockTheme(useAppColors = true),
        elements = listOf(
            e(WEATHER, TOP_START, Variants.WEATHER_BADGE),
            e(UPDATE, TOP_END),
            e(TIME, CENTER),
            e(DATE, CENTER, Variants.DATE_LONG),
            e(NOW_PLAYING, CENTER, Variants.NP_ROW),
            e(STATUS, BOTTOM_CENTER),
            e(RECORD, BOTTOM_END),
        ),
    )

    val midnight = ClockStyle(
        preset = MIDNIGHT,
        theme = ClockTheme(
            background = "#0B0D12", text = "#E8ECF3", muted = "#8A93A6", accent = "#7AA2F7",
            panel = "#1A1E27", alert = "#F87171",
            timeFont = ClockFonts.INTER, timeWeight = 200, timeSpacing = -0.03f, textFont = ClockFonts.INTER,
        ),
        elements = listOf(
            e(WEATHER, TOP_START, Variants.WEATHER_COMPACT),
            e(NEXT_EVENT, TOP_END, Variants.NEXT_COMPACT),
            e(UPDATE, TOP_END),
            e(TIME, CENTER, scale = 1.2f),
            e(DATE, CENTER, Variants.DATE_CAPS),
            e(HEADLINE, CENTER, Variants.HEADLINE_PLAIN),
            e(RECORD, BOTTOM_START),
            e(STATUS, BOTTOM_CENTER),
            e(NOW_PLAYING, BOTTOM_END, Variants.NP_PILL),
        ),
    )

    val paper = ClockStyle(
        preset = PAPER,
        theme = ClockTheme(
            background = "#F3EEE4", text = "#2B2620", muted = "#6B6157", accent = "#B4532A",
            panel = "#E9E2D4", alert = "#B4532A",
            timeFont = ClockFonts.PLAYFAIR, timeWeight = 400, timeSpacing = -0.02f, textFont = ClockFonts.MANROPE,
        ),
        elements = listOf(
            e(TIME, TOP_START, Variants.TIME_WEEKDAY, scale = 1.05f),
            e(DATE, TOP_START, Variants.DATE_ELEGANT),
            e(WEATHER, TOP_START, Variants.WEATHER_TEXT),
            e(NOW_PLAYING, BOTTOM_START, Variants.NP_LINE),
            e(RECORD, BOTTOM_START),
            e(STATUS, BOTTOM_CENTER),
            e(TODAY_PANEL, CENTER_END, Variants.PANEL_COLUMN, width = 0.36f, fillHeight = true),
            e(UPDATE, TOP_END),
        ),
    )

    val sunset = ClockStyle(
        preset = SUNSET,
        theme = ClockTheme(
            background = "#1D1238", gradient = "#E07A5F", text = "#FFFFFF", muted = "#E6DDF0",
            accent = "#FFD6A5", panel = "#33FFFFFF", alert = "#FFD6A5",
            timeFont = ClockFonts.OUTFIT, timeWeight = 200, timeSpacing = -0.02f, textFont = ClockFonts.OUTFIT,
        ),
        elements = listOf(
            e(NOW_PLAYING, TOP_START, Variants.NP_PILL),
            e(NEXT_EVENT, TOP_END, Variants.NEXT_CARD),
            e(UPDATE, TOP_END),
            e(DATE, BOTTOM_START, Variants.DATE_LONG),
            e(TIME, BOTTOM_START, scale = 1.15f),
            e(STATUS, BOTTOM_CENTER),
            e(WEATHER, BOTTOM_END, Variants.WEATHER_LARGE),
            e(RECORD, CENTER_END),
        ),
    )

    val terminal = ClockStyle(
        preset = TERMINAL,
        theme = ClockTheme(
            background = "#0C0F0A", text = "#C5D1B5", muted = "#6B7A5C", accent = "#9ECE6A",
            panel = "#1A2014", alert = "#F7768E", highlight = "#E0AF68",
            timeFont = ClockFonts.FIRA, timeWeight = 300, textFont = ClockFonts.FIRA, terminal = true,
        ),
        elements = listOf(
            e(TIME, TOP_START, scale = 0.85f),
            e(DATE, TOP_START, Variants.DATE_ISO),
            e(WEATHER, TOP_START, Variants.WEATHER_TEXT),
            e(NEXT_EVENT, TOP_START, Variants.NEXT_LINE),
            e(TODAY_PANEL, TOP_END, Variants.PANEL_TERMINAL, width = 0.46f),
            e(UPDATE, TOP_END),
            e(RECORD, CENTER_END),
            e(STATUS, BOTTOM_START),
            e(NOW_PLAYING, BOTTOM_CENTER, Variants.NP_BAR),
        ),
    )

    val bold = ClockStyle(
        preset = BOLD,
        theme = ClockTheme(
            background = "#101418", text = "#F3F4F6", muted = "#9AA4B2", accent = "#FFB547",
            panel = "#171D24", alert = "#F87171",
            timeFont = ClockFonts.BEBAS, timeWeight = 400, timeSpacing = 0.01f, textFont = ClockFonts.MANROPE,
            amPmAccent = true,
        ),
        elements = listOf(
            e(WEATHER, TOP_START, Variants.WEATHER_COMPACT),
            e(TIME, CENTER_START, scale = 1.55f),
            e(DATE, CENTER_START, Variants.DATE_CAPS),
            e(NOW_PLAYING, BOTTOM_START, Variants.NP_MINI),
            e(STATUS, BOTTOM_START),
            e(TODAY_PANEL, CENTER_END, Variants.PANEL_CARD, width = 0.45f, fillHeight = true),
            e(UPDATE, TOP_START),
            e(RECORD, BOTTOM_CENTER),
        ),
    )

    val album = ClockStyle(
        preset = ALBUM,
        theme = ClockTheme(
            background = "#16161C", text = "#FFFFFF", muted = "#D0D0D8", accent = "#FFFFFF",
            panel = "#33FFFFFF", alert = "#FF8A80",
            timeFont = ClockFonts.DM_SERIF, timeWeight = 400, textFont = ClockFonts.INTER,
            artworkBackground = true,
        ),
        elements = listOf(
            e(TIME, TOP_START, scale = 0.9f),
            e(DATE, TOP_START, Variants.DATE_LONG),
            e(WEATHER, TOP_START, Variants.WEATHER_COMPACT),
            e(SUMMARY, TOP_END, Variants.SUMMARY_LINES),
            e(UPDATE, TOP_END),
            e(NOW_PLAYING, BOTTOM_CENTER, Variants.NP_FULL),
            e(STATUS, BOTTOM_CENTER),
            e(RECORD, CENTER_END),
        ),
    )

    /** The Classic layout and font in dim red on black, for a dark bedroom (the night look's default). */
    val nightstand = ClockStyle(
        preset = NIGHTSTAND,
        theme = ClockTheme(
            // Classic's secondary text is its text at 65 % over the background; same here.
            background = "#000000", text = "#B3261E", muted = "#741914", accent = "#B3261E",
            panel = "#140303", alert = "#B3261E",
            timeFont = ClockFonts.FIRA, timeWeight = 300, textFont = ClockFonts.FIRA,
            dim = 0.85f,
        ),
        elements = classic.elements,
    )

    val all: List<ClockStyle> = listOf(classic, midnight, paper, sunset, terminal, bold, album, nightstand)

    fun byId(id: String): ClockStyle? = all.firstOrNull { it.preset == id }

    fun label(id: String) = when (id) {
        CLASSIC -> "Classic"
        MIDNIGHT -> "Midnight"
        PAPER -> "Paper"
        SUNSET -> "Sunset"
        TERMINAL -> "Terminal"
        BOLD -> "Bold split"
        ALBUM -> "Album"
        NIGHTSTAND -> "Nightstand"
        else -> id
    }
}
