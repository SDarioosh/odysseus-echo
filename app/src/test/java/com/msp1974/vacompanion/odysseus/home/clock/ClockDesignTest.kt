package com.msp1974.vacompanion.odysseus.home.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockDesignTest {

    @Test
    fun `palettes have unique ids, valid colours and readable daytime text`() {
        assertEquals(ClockPalettes.all.size, ClockPalettes.all.map { it.id }.toSet().size)
        ClockPalettes.all.forEach { p ->
            listOf(p.background, p.text, p.muted, p.accent, p.panel, p.alert).forEach { assertTrue("${p.id} $it", ClockColor.parse(it) != null) }
            p.gradient?.let { assertTrue(p.id, ClockColor.parse(it) != null) }
            val theme = p.applyTo(ClockTheme())
            if (!p.night) {
                assertTrue("${p.id} text", ClockContrast.worst(p.text, theme)!! >= 4.5)
                assertTrue("${p.id} muted", ClockContrast.worst(p.muted, theme)!! >= 3.0)
            }
            assertEquals(p.id, emptyList<String>(), ClockContrast.warnings(theme))
        }
    }

    @Test
    fun `every preset's colours are one of the schemes`() {
        ClockPresets.all.filter { !it.theme.useAppColors }.forEach { p ->
            assertTrue(p.preset, ClockPalettes.matching(p.theme) != null)
        }
    }

    @Test
    fun `applying a scheme turns the app's colours off and keeps the fonts`() {
        val theme = ClockTheme(useAppColors = true, timeFont = ClockFonts.BEBAS)
        val p = ClockPalettes.byId("ocean")!!
        val applied = p.applyTo(theme)
        assertFalse(applied.useAppColors)
        assertEquals(ClockFonts.BEBAS, applied.timeFont)
        assertEquals(p, ClockPalettes.matching(applied))
        // A colour changed by hand is no scheme any more.
        assertNull(ClockPalettes.matching(applied.copy(text = "#123456")))
        // The app's colours match no scheme.
        assertNull(ClockPalettes.matching(theme))
    }

    @Test
    fun `font pairs use known fonts and are matched`() {
        assertEquals(ClockFontPairs.all.size, ClockFontPairs.all.map { it.id }.toSet().size)
        ClockFontPairs.all.forEach { f ->
            assertTrue(f.id, f.timeFont in ClockFonts.ALL && f.textFont in ClockFonts.ALL)
            assertEquals(f, ClockFontPairs.matching(f.applyTo(ClockTheme())))
        }
        // Every preset's fonts are one of the pairs.
        ClockPresets.all.forEach { assertTrue(it.preset, ClockFontPairs.matching(it.theme) != null) }
    }

    @Test
    fun `contrast ratio`() {
        assertEquals(21.0, ClockContrast.ratio("#000000", "#FFFFFF")!!, 0.01)
        assertEquals(1.0, ClockContrast.ratio("#777777", "#777777")!!, 0.001)
        assertNull(ClockContrast.ratio("nope", "#FFFFFF"))
    }

    @Test
    fun `hard to read colours are warned about, the app's colours never`() {
        val grey = ClockTheme(background = "#777777", text = "#808080", muted = "#7A7A7A")
        assertEquals(2, ClockContrast.warnings(grey).size)
        assertTrue(ClockContrast.warnings(grey.copy(useAppColors = true)).isEmpty())
    }

    @Test
    fun `a gradient counts where most text sits`() {
        // Black text reads on white, but not halfway to a black gradient end.
        val t = ClockTheme(background = "#FFFFFF", gradient = "#000000", text = "#000000", muted = "#000000")
        assertTrue(ClockContrast.ratio(t.text, t.background)!! > 20)
        assertTrue(ClockContrast.worst(t.text, t)!! < 6)
    }

    @Test
    fun `anchors read as words`() {
        assertEquals("Top left", Anchor.TOP_START.label)
        assertEquals("Middle", Anchor.CENTER.label)
        assertEquals("Bottom centre", Anchor.BOTTOM_CENTER.label)
        assertEquals("Middle right", Anchor.CENTER_END.label)
        assertEquals(Anchor.entries.size, Anchor.entries.map { it.label }.toSet().size)
    }

    @Test
    fun `move down is the reverse of move up`() {
        val s = ClockStyle.normalized(ClockPresets.classic)
        val down = s.movedDown(ElementType.TIME)
        assertEquals(listOf(ElementType.DATE, ElementType.TIME, ElementType.NOW_PLAYING), down.shownAt(Anchor.CENTER))
        assertEquals(s, down.movedUp(ElementType.TIME))
        // The last at its anchor stays put.
        assertEquals(s, s.movedDown(ElementType.NOW_PLAYING))
    }

    @Test
    fun `stack place counts only visible elements at the same anchor`() {
        val s = ClockStyle.normalized(ClockPresets.classic)
        assertEquals(1 to 3, s.stackPlace(ElementType.TIME))
        assertEquals(3 to 3, s.stackPlace(ElementType.NOW_PLAYING))
        assertEquals(1 to 1, s.stackPlace(ElementType.WEATHER))
    }

    @Test
    fun `reading order goes row by row, left to right, then stack order`() {
        val s = ClockStyle.normalized(ClockPresets.classic)
        assertEquals(
            listOf(ElementType.WEATHER, ElementType.UPDATE, ElementType.TIME, ElementType.DATE, ElementType.NOW_PLAYING, ElementType.STATUS, ElementType.RECORD),
            s.shownInReadingOrder().map { it.type },
        )
    }

    @Test
    fun `sizes read as the nearest name`() {
        assertEquals("Medium", sizeLabel(1f))
        assertEquals("Large", sizeLabel(1.15f))
        assertEquals("Extra large", sizeLabel(2.5f))
        val el = ClockElement(ElementType.WEATHER, Anchor.TOP_START, scale = 1.25f, variant = Variants.WEATHER_LARGE)
        assertEquals("Top left · Large · large", el.summary { _, v -> v })
        assertEquals("Bottom right", ClockElement(ElementType.RECORD, Anchor.BOTTOM_END).summary { _, v -> v })
    }

    private fun ClockStyle.shownAt(a: Anchor) = elements.filter { it.visible && it.anchor == a }.map { it.type }
}
