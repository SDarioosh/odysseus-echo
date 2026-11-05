package com.msp1974.vacompanion.sendspin

// Odysseus fork: what the Sendspin player knows about its connection's security (sendspin-cpp
// v0.9 encrypts every connection; pairing proves which server is on the other end), for the
// Configuration screen and the pairing-code overlay. The rules are plain functions on
// [SendspinSecurityState] (unit-tested); [SendspinSecurity] holds the current state for the UI.

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How a pairing attempt ended. [atMs] is on the elapsed-realtime clock. */
data class SendspinPairingResult(val succeeded: Boolean, val reason: String?, val atMs: Long)

data class SendspinSecurityState(
    val running: Boolean = false,
    /** The id servers know this player by (base64url of the device's public key). */
    val clientId: String = "",
    val connected: Boolean = false,
    val serverName: String? = null,
    /** The connected server used this device's pairing record in its handshake. */
    val paired: Boolean = false,
    /** Whether servers that haven't paired may play. */
    val unpairedAccess: Boolean = true,
    /** Six digits to show for a pairing attempt, or null. */
    val pairingCode: String? = null,
    /** An attempt waits for the operator to allow pairing on the device. */
    val windowRequested: Boolean = false,
    /** A pairing exchange is under way. */
    val pairing: Boolean = false,
    val lastResult: SendspinPairingResult? = null,
) {
    fun started(clientId: String, unpairedAccess: Boolean) =
        SendspinSecurityState(running = true, clientId = clientId, unpairedAccess = unpairedAccess)

    fun stopped() = SendspinSecurityState(unpairedAccess = unpairedAccess)

    /** A disconnect ends any attempt on that connection, and with it what the server proved. */
    fun connection(connected: Boolean, serverName: String?) =
        if (connected) {
            copy(connected = true, serverName = serverName)
        } else {
            copy(
                connected = false, serverName = null, paired = false, pairingCode = null,
                windowRequested = false, pairing = false,
            )
        }

    fun trust(paired: Boolean) = copy(paired = paired)

    fun code(code: String?) = copy(pairingCode = code?.takeIf { it.isNotBlank() })

    fun window(open: Boolean) = copy(windowRequested = open)

    /** [stage]: 0 started, 1 succeeded, 2 failed (see NativeSendspinCallbacks.onPairingResult). */
    fun result(stage: Int, reason: String?, nowMs: Long) = when (stage) {
        0 -> copy(pairing = true, lastResult = null)
        1 -> copy(pairing = false, pairingCode = null, windowRequested = false,
            lastResult = SendspinPairingResult(true, null, nowMs))
        else -> copy(pairing = false, pairingCode = null,
            lastResult = SendspinPairingResult(false, reason, nowMs))
    }

    /** One line for the Configuration screen. */
    fun label(): String = when {
        !running -> "Not running"
        !connected -> "Encrypted, no server connected"
        paired -> "Encrypted, paired with ${serverName ?: "server"}"
        else -> "Encrypted, not paired"
    }

    /** Whether the pairing overlay has something to show at [nowMs]. */
    fun overlayVisible(nowMs: Long): Boolean =
        pairingCode != null || windowRequested ||
            (lastResult != null && nowMs - lastResult.atMs < RESULT_SHOWN_MS)

    companion object {
        const val RESULT_SHOWN_MS = 6_000L

        fun reasonText(reason: String?): String = when (reason) {
            "code_mismatch" -> "the code didn't match"
            "timeout" -> "it timed out"
            "cancelled" -> "it was cancelled"
            "concurrent_attempt" -> "another pairing was in progress"
            "method_not_supported" -> "the server asked for a method this device doesn't offer"
            else -> "it was stopped"
        }
    }
}

/** Process-wide holder: written by [SendspinPlayer], read by the UI. */
object SendspinSecurity {
    private val _state = MutableStateFlow(SendspinSecurityState())
    val state: StateFlow<SendspinSecurityState> = _state.asStateFlow()

    /** Set by the running player: allow (true) or refuse (false) the pairing window. */
    @Volatile
    var onPairingWindowAnswer: ((Boolean) -> Unit)? = null

    fun update(change: (SendspinSecurityState) -> SendspinSecurityState) = _state.update(change)

    fun answerPairingWindow(allow: Boolean) {
        onPairingWindowAnswer?.invoke(allow)
    }
}
