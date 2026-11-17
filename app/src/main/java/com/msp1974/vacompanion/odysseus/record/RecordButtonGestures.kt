package com.msp1974.vacompanion.odysseus.record

// Odysseus fork: the Echo Show's privacy button as the record button (docs/odysseus/recording.md,
// "The privacy button"). Plain Kotlin, no Android: RecordButton.kt feeds it key events, switch
// changes and timer ticks, and runs what it returns. Unit-tested (RecordButtonGesturesTest).
//
// The button reaches the app two ways, and either may be missing:
//   - key events (gpio-privacy-button, scancode 116 mapped to MEDIA_RECORD): only while the
//     display is on and the activity has focus. Down and up give press vs hold.
//   - the mute switch (gpio-privacy-state, SW_MUTE_DEVICE): Android's microphone-mute flag flips on
//     every press, also with the display off or another app in front. No hold possible.
// Both come from one press, so a switch change is only a press when no key event covers it: it
// waits SWITCH_WAIT_MS for a key down (which may arrive after it), and is dropped while a key is
// down or within KEY_COVERS_SWITCH_MS of the last key event.

/** What the privacy button does (Menu → Odysseus → Recording). */
enum class PrivacyButtonMode(val key: String) {
    /**
     * Press: record / stop. Hold 1 s: voice note. Muting is the quick actions' "Mic Mute".
     * Not the default: on the Echo Show 5 (2nd gen) the press also cuts the microphone, so what
     * it records is silent until the next press.
     */
    RECORD("record"),

    /** Upstream behaviour, the default: the button only mutes the microphone. */
    MUTE("mute");

    companion object {
        fun fromKey(key: String?): PrivacyButtonMode = entries.firstOrNull { it.key == key } ?: MUTE
    }
}

sealed interface ButtonAction {
    /** Call RecorderController.press(). */
    data object Press : ButtonAction

    /** Call RecorderController.hold(). */
    data object Hold : ButtonAction

    /** Call holdTimerFired() after [delayMs]. */
    data class StartHoldTimer(val delayMs: Long) : ButtonAction

    /** Call switchTimerFired() after [delayMs]. */
    data class StartSwitchTimer(val delayMs: Long) : ButtonAction
}

object PrivacyButtonRules {
    /** Input device names of the Echo Show 5 (2nd gen) privacy button on LineageOS 18.1. */
    const val KEY_DEVICE = "gpio-privacy-button"
    const val SWITCH_DEVICE = "gpio-privacy-state"

    /** Whether this device has the privacy button (tablets don't: the setting does nothing there). */
    fun hasPrivacyButton(inputDeviceNames: Collection<String>): Boolean =
        inputDeviceNames.any { it == KEY_DEVICE || it == SWITCH_DEVICE }

    /** The button records (rather than mutes): it exists, is set to Record, and recording is on. */
    fun isRecordButton(hasButton: Boolean, mode: PrivacyButtonMode, recordingEnabled: Boolean): Boolean =
        hasButton && mode == PrivacyButtonMode.RECORD && recordingEnabled

    /**
     * Whether Android's microphone-mute flag counts as muted for the app ("Microphone off", the
     * recorder's refusal). Not while the button records: then every press flips the flag (and the
     * press also cuts the microphone in hardware, which the app can't undo).
     */
    fun systemMuteCounts(rawMuted: Boolean, recordButton: Boolean): Boolean = rawMuted && !recordButton
}

class RecordButtonGestures(
    private val holdMs: Long = RecorderStateMachine.HOLD_MS,
    private val switchWaitMs: Long = SWITCH_WAIT_MS,
    private val keyCoversSwitchMs: Long = KEY_COVERS_SWITCH_MS,
) {
    private var down = false
    private var downAt = 0L
    private var held = false
    private var lastKeyAt: Long? = null
    private var switchAt: Long? = null

    /** A key down. [repeatCount] > 0 is auto-repeat and ignored. */
    fun keyDown(now: Long, repeatCount: Int = 0): List<ButtonAction> {
        lastKeyAt = now
        switchAt = null // this press is the key's
        if (repeatCount > 0) return emptyList()
        // A down while "down" means the last up was lost (focus changed): start again.
        down = true
        downAt = now
        held = false
        return listOf(ButtonAction.StartHoldTimer(holdMs))
    }

    /** A key up: a press if it came before the hold fired; nothing after a hold. */
    fun keyUp(now: Long): List<ButtonAction> {
        lastKeyAt = now
        if (!down) return emptyList()
        down = false
        return if (held) emptyList() else listOf(ButtonAction.Press)
    }

    /** The hold timer of a key down fired. A stale timer (from an earlier down) does nothing. */
    fun holdTimerFired(now: Long): List<ButtonAction> {
        if (!down || held || now - downAt < holdMs) return emptyList()
        held = true
        return listOf(ButtonAction.Hold)
    }

    /** Android's microphone-mute flag flipped (the switch). */
    fun switchChanged(now: Long): List<ButtonAction> {
        if (down || keyCoversSwitch(now)) return emptyList()
        switchAt = now
        return listOf(ButtonAction.StartSwitchTimer(switchWaitMs))
    }

    /** The wait after a switch change ended: a press, unless a key event took it meanwhile. */
    fun switchTimerFired(now: Long): List<ButtonAction> {
        val at = switchAt ?: return emptyList()
        if (now - at < switchWaitMs) return emptyList()
        switchAt = null
        if (down || (lastKeyAt ?: Long.MIN_VALUE) >= at) return emptyList()
        return listOf(ButtonAction.Press)
    }

    /** Forget a gesture in progress (the mode changed). */
    fun reset() {
        down = false
        held = false
        switchAt = null
    }

    private fun keyCoversSwitch(now: Long): Boolean {
        val last = lastKeyAt ?: return false
        return now - last <= keyCoversSwitchMs
    }

    companion object {
        /** How long a switch change waits for the same press's key down. */
        const val SWITCH_WAIT_MS = 300L

        /** A switch change this soon after a key event is that key's press (or hold) again. */
        const val KEY_COVERS_SWITCH_MS = 1_500L
    }
}
