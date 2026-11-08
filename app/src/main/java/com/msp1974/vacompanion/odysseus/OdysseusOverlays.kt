package com.msp1974.vacompanion.odysseus

// Odysseus fork: what MainActivity.kt shows of the fork over any layout, and the events that turn
// its screen on. MainActivity's hooks are one line each: OdysseusOverlays(...) after the layout and
// `in OdysseusUi.SCREEN_ON_EVENTS` in its event handler.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.msp1974.vacompanion.odysseus.announce.AnnouncementCard
import com.msp1974.vacompanion.odysseus.announce.Announcements
import com.msp1974.vacompanion.odysseus.lock.LockMode
import com.msp1974.vacompanion.odysseus.lock.LockOverlay
import com.msp1974.vacompanion.odysseus.record.RecorderController
import com.msp1974.vacompanion.odysseus.record.RecordingPill

/**
 * The fork's overlays, in drawing order (the last on top). [satelliteRunning]: the voice overlays
 * only show while the satellite runs. [onLocked]: Lock mode just locked (or was locked when this
 * was first composed); MainActivity closes its settings menu.
 */
@Composable
fun OdysseusOverlays(satelliteRunning: Boolean, onLocked: () -> Unit) {
    // Listening/thinking/answering indicator (ListeningIndicator.kt).
    if (satelliteRunning) ListeningIndicator()
    // What a voice turn did: actions, sources, confirmation (TurnCard.kt).
    if (satelliteRunning) TurnCard()
    // Announcement / reminder card (announce/AnnouncementCard.kt).
    AnnouncementCard()
    // Sendspin pairing code / approval (SendspinPairingOverlay.kt).
    SendspinPairingOverlay()
    // Red REC / NOTE pill over every screen while recording (record/RecordingUi.kt).
    RecordingPill()
    // Lock mode covers everything above while locked (lock/LockOverlay.kt).
    LockOverlay()

    // Lock mode closes the settings menu when it locks.
    val latestOnLocked = rememberUpdatedState(onLocked)
    LaunchedEffect(Unit) {
        LockMode.locked.collect { locked -> if (locked) latestOnLocked.value() }
    }
}

object OdysseusUi {
    /** Events that turn MainActivity's screen on (ScreenOnMode.ON). */
    val SCREEN_ON_EVENTS = setOf(
        "sendspinPairingPrompt", // show the pairing code
        Announcements.EVENT_NEW, // show the announcement card
        RecorderController.EVENT_STARTED, // show the REC pill
    )
}
