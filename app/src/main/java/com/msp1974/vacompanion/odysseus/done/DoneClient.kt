package com.msp1974.vacompanion.odysseus.done

// Odysseus fork: sends the Done and Undo requests of contract C5 (DoneApi.kt). HTTP goes through
// DoneHttp so the client is unit-tested with a fake (DoneClientTest); on the device it is
// OdysseusLink.hubRequest, asked for error bodies so a 404 "not found" is told from a missing
// route. Never throws (but passes cancellation on).

import com.msp1974.vacompanion.odysseus.OdysseusLink
import kotlin.coroutines.cancellation.CancellationException

/** One JSON POST to the hub, path relative to the voice base URL. Throws on network errors. */
interface DoneHttp {
    suspend fun post(path: String, json: String): OdysseusLink.HubReply
}

/** The device's link to Odysseus. */
object LinkDoneHttp : DoneHttp {
    override suspend fun post(path: String, json: String): OdysseusLink.HubReply =
        OdysseusLink.hubRequest(path, "application/json", json.toByteArray(), readTimeoutMs = 10_000, errorBody = true)
}

class DoneClient(private val http: DoneHttp = LinkDoneHttp) {

    /** [title]: the row's text as shown, sent for a line named by its index (DoneApi.sendsTitle). */
    suspend fun done(target: DoneTarget, deviceId: String, title: String? = null): DoneOutcome =
        send(DoneApi.donePath(target), DoneApi.body(target, deviceId, title))

    suspend fun undo(target: DoneTarget, deviceId: String): DoneOutcome {
        val path = DoneApi.undoPath(target)
            ?: return DoneOutcome.Failed(DoneOutcome.Kind.NOT_SUPPORTED, "Can't be undone")
        return send(path, DoneApi.body(target, deviceId))
    }

    private suspend fun send(path: String, json: String): DoneOutcome = try {
        val r = http.post(path, json)
        DoneApi.classify(r.code, r.body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DoneApi.unreachable()
    }
}
