package com.msp1974.vacompanion.odysseus.home.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockStyleTest {

    @Test
    fun `nothing stored gives the classic preset with every element listed`() {
        val s = ClockStyleCodec.decode(null)
        assertEquals(ClockPresets.CLASSIC, s.preset)
        assertTrue(s.theme.useAppColors)
        assertEquals(ElementType.entries.toSet(), s.elements.map { it.type }.toSet())
        assertEquals(ElementType.entries.size, s.elements.size)
        assertTrue(s.isPresetUnchanged)
    }

    @Test
    fun `unreadable json falls back to classic`() {
        assertEquals(ClockPresets.CLASSIC, ClockStyleCodec.decode("{not json").preset)
        assertEquals(ClockPresets.CLASSIC, ClockStyleCodec.decode("""{"elements":[{"type":"NO_SUCH"}]}""").preset)
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        ClockPresets.all.forEach { p ->
            val n = ClockStyle.normalized(p)
            assertEquals(p.preset, n, ClockStyleCodec.decode(ClockStyleCodec.encode(n)))
            assertTrue(p.preset, n.isPresetUnchanged)
        }
    }

    @Test
    fun `presets have unique ids, a visible time, and readable colours`() {
        assertEquals(ClockPresets.all.size, ClockPresets.all.map { it.preset }.toSet().size)
        ClockPresets.all.forEach { p ->
            assertTrue(p.preset, p.element(ElementType.TIME).visible)
            val t = p.theme
            listOf(t.background, t.text, t.muted, t.accent, t.panel, t.alert).forEach { assertTrue("${p.preset} $it", ClockColor.parse(it) != null) }
            t.gradient?.let { assertTrue(ClockColor.parse(it) != null) }
            assertTrue(t.timeFont in ClockFonts.ALL && t.textFont in ClockFonts.ALL)
            // Each type at most once, so no element is silently dropped by normalized().
            assertEquals(p.preset, p.elements.size, p.elements.map { it.type }.toSet().size)
            // Variants named in presets exist.
            p.elements.forEach { e -> assertTrue("${p.preset} ${e.type} ${e.variant}", e.variant.isEmpty() || e.variant in e.type.variants) }
        }
    }

    @Test
    fun `a change is kept and marks the preset as changed`() {
        val s = ClockStyle.normalized(ClockPresets.midnight)
        val changed = s.withElement(s.element(ElementType.NOW_PLAYING).copy(anchor = Anchor.TOP_START))
        assertFalse(changed.isPresetUnchanged)
        val back = ClockStyleCodec.decode(ClockStyleCodec.encode(changed))
        assertEquals(Anchor.TOP_START, back.element(ElementType.NOW_PLAYING).anchor)
        assertEquals(ClockPresets.MIDNIGHT, back.preset)
    }

    @Test
    fun `normalized keeps the first of a duplicate, clamps values and fixes unknown fonts`() {
        val s = ClockStyle.normalized(
            ClockStyle(
                theme = ClockTheme(timeFont = "comic", dim = 0f, timeWeight = 5000),
                elements = listOf(
                    ClockElement(ElementType.TIME, Anchor.TOP_START, scale = 9f),
                    ClockElement(ElementType.TIME, Anchor.CENTER),
                ),
            )
        )
        assertEquals(Anchor.TOP_START, s.element(ElementType.TIME).anchor)
        assertEquals(2.5f, s.element(ElementType.TIME).scale)
        assertEquals(ClockFonts.FIRA, s.theme.timeFont)
        assertEquals(0.2f, s.theme.dim)
        assertEquals(900, s.theme.timeWeight)
        assertFalse(s.element(ElementType.DATE).visible)
    }

    @Test
    fun `unknown variant draws the type's default`() {
        assertEquals(Variants.NP_ROW, ClockElement(ElementType.NOW_PLAYING, variant = "hologram").style)
        assertEquals(Variants.NP_PILL, ClockElement(ElementType.NOW_PLAYING, variant = Variants.NP_PILL).style)
    }

    @Test
    fun `unknown keys in stored json are ignored`() {
        val s = ClockStyleCodec.decode("""{"preset":"paper","future":1,"theme":{"background":"#101010","sparkle":true}}""")
        assertEquals("paper", s.preset)
        assertEquals("#101010", s.theme.background)
    }

    @Test
    fun `move up swaps with the visible element before it at the same anchor`() {
        val s = ClockStyle.normalized(ClockPresets.classic)
        val moved = s.movedUp(ElementType.DATE)
        val center = moved.elements.filter { it.visible && it.anchor == Anchor.CENTER }.map { it.type }
        assertEquals(listOf(ElementType.DATE, ElementType.TIME, ElementType.NOW_PLAYING), center)
        // The first at its anchor stays put.
        assertEquals(moved, moved.movedUp(ElementType.DATE))
    }

    @Test
    fun `colour parsing`() {
        assertEquals(0xFF282C34L, ClockColor.parse("#282C34"))
        assertEquals(0x33FFFFFFL, ClockColor.parse("#33ffffff"))
        assertNull(ClockColor.parse("#12345"))
        assertNull(ClockColor.parse("blue"))
        assertNull(ClockColor.parse(null))
        ClockColor.PALETTE.forEach { assertTrue(it, ClockColor.parse(it) != null) }
    }
}
