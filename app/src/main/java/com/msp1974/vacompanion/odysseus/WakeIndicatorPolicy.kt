package com.msp1974.vacompanion.odysseus

// Odysseus fork: the one owner of "this wake word ended without a turn" for the listening indicator
// (docs/odysseus/turn-card.md#listening-indicator).
//
// Satellite.handleWakeWordDetection shows "Listening…" the moment a wake word is heard, before this
// device knows whether it answers. From then on exactly one of two things happens:
//   - a turn starts (OdysseusSatellite.beginTurn): the pipeline's stages drive the indicator;
//   - the wake word ends without one, whatever the reason (lost arbitration, a report told it lost
//     after the decision, Lock mode, HA's previous run never ended, the kept audio was dropped, an
//     error): the handler's `finally` calls wakeEnded() and the indicator goes back to idle at once.
// Before this, every early return had to remember showIdle(), and the lost-arbitration one didn't,
// so a device that lost showed "Listening…" for VoiceActivity.MAX_VISIBLE_MS (90 s), which also kept
// announcement cards waiting (AnnouncementRules.turnActive).
//
// While a wake word is pending no turn of this device has started yet, so any pipeline stage reported
// meanwhile is the end of an older turn (a barge-in stops the old pipeline, whose ENDED arrives
// asynchronously): it must not hide the new "Listening…" (stageShown()).

class WakeIndicatorPolicy(
    private val show: () -> Unit,
    private val hide: () -> Unit,
) {
    private var pending = false

    /** True while a wake word has shown "Listening…" and neither a turn nor its end has followed. */
    val isPending: Boolean
        @Synchronized get() = pending

    /** A wake word was heard and this device is finding out whether it answers. */
    @Synchronized
    fun wakeHeard() {
        pending = true
        show()
    }

    /** A turn started: from now on its pipeline stages own the indicator. */
    @Synchronized
    fun turnStarted() {
        pending = false
    }

    /** The wake word's handling is over. If no turn started, nothing will clear the indicator: do it now. */
    @Synchronized
    fun wakeEnded() {
        if (!pending) return
        pending = false
        hide()
    }

    /** Whether a pipeline stage may drive the indicator: not one of an older turn while a wake word is pending. */
    @Synchronized
    fun stageShown(): Boolean = !pending
}
