package com.msp1974.vacompanion.odysseus.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOffRulesTest {
    private fun look(canLock: Boolean = true, startup: Boolean = false, touchEnds: Boolean = false, chosen: Boolean = false) =
        ScreenOffPolicy.screensaver(canLock, startup, touchEnds, chosen).look

    @Test
    fun panelOffWithDeviceAdminWhenTouchDoesNotEndIt() {
        assertEquals(ScreensaverLook.PANEL_OFF, look())
    }

    @Test
    fun dimWithoutDeviceAdmin() {
        assertEquals(ScreensaverLook.DIM_BLACK, look(canLock = false))
        assertEquals(ScreensaverLook.DIM_BLACK, look(canLock = false, chosen = true))
    }

    @Test
    fun dimAtStartUpWhileTheDashboardLoads() {
        assertEquals(ScreensaverLook.DIM_BLACK, look(startup = true, chosen = true))
    }

    @Test
    fun touchToEndKeepsThePanelLitUnlessTheOwnerChoseFullOff() {
        assertEquals(ScreensaverLook.DIM_BLACK, look(touchEnds = true))
        assertEquals(ScreensaverLook.PANEL_OFF, look(touchEnds = true, chosen = true))
    }

    @Test
    fun cameraMotionWakeKeepsThePanelLitUnlessTheOwnerChoseFullOff() {
        assertEquals(ScreensaverLook.DIM_BLACK, ScreenOffPolicy.screensaver(true, false, false, false, motionWakes = true).look)
        assertEquals(ScreensaverLook.PANEL_OFF, ScreenOffPolicy.screensaver(true, false, false, true, motionWakes = true).look)
    }

    @Test
    fun theAppsOwnLockIsNotReportedAsScreenOff() {
        val s = ScreenOffState()
        assertFalse(s.systemScreenOff())
        s.entered()
        assertTrue(s.systemScreenOff())
        // A wake word turns it on before the screen-off broadcast arrived: still not reported.
        s.left()
        assertTrue(s.systemScreenOff())
        assertFalse(s.systemScreenOn()) // the app woke it: the screensaver already ended
        assertFalse(s.systemScreenOff()) // a later screen-off (Home Assistant, timeout) is reported
    }

    @Test
    fun aPowerButtonEndsThePanelOffScreensaver() {
        val s = ScreenOffState()
        s.entered()
        assertTrue(s.systemScreenOn())
        assertFalse(s.offForScreensaver)
        assertFalse(s.systemScreenOn())
    }

    @Test
    fun endingThePanelOffScreensaverTurnsTheScreenOn() {
        val s = ScreenOffState()
        assertFalse(s.screensaverEndTurnsOn(screenIsOn = false)) // upstream: off stays off
        assertTrue(s.screensaverEndTurnsOn(screenIsOn = true))
        s.entered()
        assertTrue(s.screensaverEndTurnsOn(screenIsOn = false))
    }

    @Test
    fun aRefusedLockLeavesNothingBehind() {
        val s = ScreenOffState()
        s.entered()
        s.lockFailed()
        assertFalse(s.offForScreensaver)
        assertFalse(s.systemScreenOff())
    }
}
