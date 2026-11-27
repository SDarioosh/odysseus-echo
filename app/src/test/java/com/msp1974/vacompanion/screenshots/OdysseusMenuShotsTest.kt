package com.msp1974.vacompanion.screenshots

// Odysseus fork: Menu → Odysseus in sections (OdysseusSettingsLayout's rail) and the Clock face
// editor's five tabs beside its live preview (home/clock/ClockStyleSettingsSection.kt), on the
// Echo Show 5 and a tablet, dark and light. No Odysseus link and no Home Assistant: the menu as on
// a fresh device, with the Classic design.

import android.app.Application
import com.msp1974.vacompanion.odysseus.OdysseusSection
import com.msp1974.vacompanion.odysseus.OdysseusSettingsLayout
import com.msp1974.vacompanion.odysseus.home.clock.ClockFaceSettings
import com.msp1974.vacompanion.odysseus.home.clock.ClockTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class OdysseusMenuShotsTest(private val screen: Screen, private val mode: Mode) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = SettledShot.parameters()
    }

    @get:Rule val shots = SettledShot(screen, mode)

    private fun menu(section: OdysseusSection) =
        shots.capture("odysseus-menu/${section.name.lowercase()}") { OdysseusSettingsLayout(onBack = {}, initial = section) }

    @Test fun home() = menu(OdysseusSection.HOME)
    @Test fun voice() = menu(OdysseusSection.VOICE)
    @Test fun kiosk() = menu(OdysseusSection.KIOSK)
    @Test fun clockInMenu() = menu(OdysseusSection.CLOCK)

    @Test fun clockLook() = tab(ClockTab.LOOK)
    @Test fun clockColours() = tab(ClockTab.COLOURS)
    @Test fun clockFonts() = tab(ClockTab.FONTS)
    @Test fun clockLayout() = tab(ClockTab.LAYOUT)
    @Test fun clockNight() = tab(ClockTab.NIGHT)

    /** The Clock face editor on its own, on [tab]. */
    private fun tab(tab: ClockTab) =
        shots.capture("odysseus-menu/clock-${tab.name.lowercase()}", settleMs = 1_000) { ClockFaceSettings(enabled = true, initialTab = tab) }
}
