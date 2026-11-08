package com.msp1974.vacompanion.odysseus.done

// Odysseus fork: sends the Done and Undo requests of contract C5 (DoneApi.kt) through HubHttp
// (OdysseusLink on the device, a fake in DoneApiTest), asked for error bodies so the kinds of 409
// and 503 are told apart. Never throws (but passes cancellation on).

import com.msp1974.vacompanion.odysseus.HubCall
import com.msp1974.vacompanion.odysseus.HubHttp
import com.msp1974.vacompanion.odysseus.OdysseusLink
import kotlin.coroutines.cancellation.CancellationException

class DoneClient(private val http: HubHttp = OdysseusLink) {

    /** [title]: the row's text as shown, sent for a line named by its index (DoneApi.sendsTitle). */
    suspend fun done(target: DoneTarget, deviceId: String, title: String? = null): DoneOutcome =
        send(DoneApi.donePath(target), DoneApi.body(target, deviceId, title))

    suspend fun undo(target: DoneTarget, deviceId: String): DoneOutcome {
        val path = DoneApi.undoPath(target)
            ?: return DoneOutcome.Failed(DoneOutcome.Kind.OTHER, "Can't be undone")
        return send(path, DoneApi.body(target, deviceId))
    }

    private suspend fun send(path: String, json: String): DoneOutcome = try {
        val r = http.request(HubCall(path, "application/json", json.toByteArray(), readTimeoutMs = 10_000, errorBody = true))
        DoneApi.classify(r.code, r.body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DoneApi.unreachable()
    }
}
