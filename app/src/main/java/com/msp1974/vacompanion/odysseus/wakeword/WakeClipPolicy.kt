package com.msp1974.vacompanion.odysseus.wakeword

// Odysseus fork: when wake word clips may be caught, kept and sent, and how long the upload waits
// after a failure (docs/odysseus/wake-word-training.md). Plain Kotlin, unit-tested
// (WakeClipPolicyTest). Clips are microphone audio: nothing is caught, kept or sent while the
// device is locked (LockMode.isLocked).

object WakeClipPolicy {
    /** What one microphone frame does. */
    enum class Feed {
        /** Locked: drop the take and everything held in memory. */
        FORGET,
        /** Neither a take nor the catcher runs: nothing is held. */
        SKIP,
        /** Hand the frame to the catcher. */
        CATCH,
    }

    fun feed(locked: Boolean, catchMisses: Boolean, taking: Boolean): Feed = when {
        locked -> Feed.FORGET
        catchMisses || taking -> Feed.CATCH
        else -> Feed.SKIP
    }

    /** The near-miss catcher runs. */
    fun catching(catchMisses: Boolean, locked: Boolean): Boolean = catchMisses && !locked

    /** The clip's wake word, or null when the clip isn't kept (locked, or no wake word set). */
    fun keepAs(locked: Boolean, wakeWord: String?): String? =
        wakeWord?.trim()?.takeIf { !locked && it.isNotEmpty() && it != "none" }

    /** Whether an upload pass may start now. */
    enum class Gate { GO, NOTHING, LOCKED, NOT_LINKED }

    fun uploadGate(waiting: Int, locked: Boolean, linked: Boolean): Gate = when {
        waiting == 0 -> Gate.NOTHING
        locked -> Gate.LOCKED
        !linked -> Gate.NOT_LINKED
        else -> Gate.GO
    }

    const val RETRY_STEP_MS = 60_000L
    const val RETRY_MAX_MS = 15 * 60_000L
    const val AUTH_WAIT_MS = 60 * 60_000L
    const val NOT_LINKED_WAIT_MS = 5 * 60_000L

    /** How long to wait after a pass ([failures] in a row so far); null: until a clip is kept. */
    fun waitMs(pass: ClipPass, failures: Int): Long? = when (pass) {
        ClipPass.DONE -> null
        ClipPass.RETRY -> minOf(RETRY_STEP_MS * failures.coerceAtLeast(1), RETRY_MAX_MS)
        ClipPass.AUTH -> AUTH_WAIT_MS
    }

    /** The line the settings screen shows for a pass, or null when all is well. */
    fun problem(result: UploadResult): String? = when (result.pass) {
        ClipPass.DONE -> null
        ClipPass.RETRY -> "Odysseus didn't take the clips (${result.detail ?: "no answer"}); will try again"
        ClipPass.AUTH -> "Odysseus refused the token: link this Echo again"
    }

    fun problem(gate: Gate): String? = when (gate) {
        Gate.GO, Gate.NOTHING -> null
        Gate.LOCKED -> "Locked: clips wait until the device is unlocked"
        Gate.NOT_LINKED -> "Not linked to Odysseus (Menu → Odysseus); clips are kept"
    }
}
