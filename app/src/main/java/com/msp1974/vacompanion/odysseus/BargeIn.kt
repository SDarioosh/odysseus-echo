package com.msp1974.vacompanion.odysseus

// Odysseus fork: a wake word while Home Assistant still has a run for this device (a barge-in).
//
// HaRunGate drops what HA still sends for that run and holds the new question until HA has ended
// it. HA ends it only once its conversation agent has answered, and an Odysseus turn that looks
// things up often takes longer than the gate waits (15 s): the new question was then given up
// and the device went idle. So the device also tells Odysseus (POST <base>/satellite/interrupt,
// OdysseusLink.interruptTurn): Odysseus ends this device's turn at once with an empty answer,
// which HA doesn't speak, and HA ends the run within about a second. The interrupt names the
// interrupted turn's context id when the hub has given it (OdysseusTurn.contextId), so only that
// turn is cut; without one (its /satellite/turn failed or is still on its way) the hub ends this
// device's turns, as before.
//
// Called on every wake word that starts a new turn, not only while the device shows a turn:
// after 15 s of "Thinking…" Satellite.isResponding() reports the turn as stuck, but HA still has
// the run. Nothing is sent when HA has no run, and only once per interrupted run.
// Without an Odysseus link the gate waits as before.

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

object BargeIn {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True when HA had a run and it is now interrupted (its end is asked of Odysseus). */
    fun interruptHaRun(deviceId: String): Boolean {
        if (!HaRunGate.interrupted()) return false
        // The interrupted turn, read now: Satellite begins the new turn only after this call.
        val turn = OdysseusTurn.last
        turn?.markBargeIn() // its timings say it was cut short (TurnTimings)
        if (!OdysseusLink.isConfigured) {
            Timber.i("Barge-in: no Odysseus link; waiting for Home Assistant to end the run by itself")
            return true
        }
        // Null while its /satellite/turn hasn't answered (or failed): the hub then ends this device's turns.
        val contextId = turn?.contextId
        scope.launch {
            when (val r = OdysseusLink.interruptTurn(deviceId, contextId)) {
                is OdysseusLink.InterruptResult.Ok -> Timber.i("Barge-in: Odysseus ended ${r.cut} turn(s), marked ${r.marked} (context $contextId)")
                is OdysseusLink.InterruptResult.Failed -> Timber.w("Barge-in: asking Odysseus to end the turn failed: ${r.message}")
            }
        }
        return true
    }
}
