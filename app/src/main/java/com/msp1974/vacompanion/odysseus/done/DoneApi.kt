package com.msp1974.vacompanion.odysseus.done

// Odysseus fork: marking things done from the device's own screen (plan contract C5;
// docs/odysseus/home-screen.md "Done", docs/odysseus/announcements.md). A tap on the screen is
// the confirmation, so no spoken "yes" is needed. Scope `voice`, the device's own token.
//
//   POST <base>/todos/done         {"ref": "todo:41:2", "device_id": …, "title": …} -> {"ok": true, "already": false, "title": …}
//   POST <base>/reminders/done     {"ref": "todo:41",   "device_id": …} -> {"ok": true, "already": false}
//     (a reminder ref with an item index, "todo:41:2", also carries the card's "title")
//   POST <base>/action-items/done  {"id": "ai_17",      "device_id": …} -> {"ok": true, "already": false}
//   POST <base>/todos/undo         {"ref", "device_id"}   reopen within 10 min of a done by a device; 409 after
//   POST <base>/action-items/undo  {"id", "device_id"}
//   404 {"error": "not found"}, 400 malformed, 503 {"error": "athena unavailable"} or {"error": "busy"}.
//   409 {"error": "changed"} on a done: the row's line was changed or removed in Odysseus since
//   Today was fetched (the hub finds a moved line by the "title" the row showed); 409 on an undo:
//   {"error": "too late to undo"}. The app and the hub are deployed together: every route is there.
//
// Pure: paths, bodies and what an answer means, unit-tested (DoneApiTest). The requests go
// through HubHttp (DoneClient.kt), OdysseusLink on the device.

import com.msp1974.vacompanion.odysseus.HubJson
import com.msp1974.vacompanion.odysseus.hubStatusText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** What a Done button marks done. Also the key of its row's mark (DoneMarks). */
sealed interface DoneTarget {
    /** One item of a to-do note ("todo:41:2"). */
    data class Todo(val ref: String) : DoneTarget
    /** A reminder: a whole note with a due date ("todo:41"), from an announcement's `ref`. */
    data class Reminder(val ref: String) : DoneTarget
    /** An Athena action item ("ai_17"). */
    data class ActionItem(val id: String) : DoneTarget

    /** The hub has an undo route for it (reminders have none). */
    val canUndo: Boolean get() = this !is Reminder

    /** For logs: refs and ids only, never titles. */
    val label: String
        get() = when (this) {
            is Todo -> ref
            is Reminder -> "reminder $ref"
            is ActionItem -> id
        }
}

/** What a done or undo request came to. */
sealed interface DoneOutcome {
    /** Done (or undone). [already]: it was so before this request. [title] from todos/done. */
    data class Ok(val already: Boolean = false, val title: String? = null) : DoneOutcome

    data class Failed(val kind: Kind, val reason: String) : DoneOutcome

    enum class Kind { NOT_FOUND, TOO_LATE, CHANGED, ATHENA_UNAVAILABLE, BUSY, REFUSED, BAD_REQUEST, UNREACHABLE, OTHER }
}

object DoneApi {
    fun donePath(target: DoneTarget): String = when (target) {
        is DoneTarget.Todo -> "/todos/done"
        is DoneTarget.Reminder -> "/reminders/done"
        is DoneTarget.ActionItem -> "/action-items/done"
    }

    /** Null for a target without an undo route. */
    fun undoPath(target: DoneTarget): String? = when (target) {
        is DoneTarget.Todo -> "/todos/undo"
        is DoneTarget.Reminder -> null
        is DoneTarget.ActionItem -> "/action-items/undo"
    }

    private val INDEXED_REF = Regex("""^todo:[^:]+:\d+$""")

    /** Whether a done of [target] carries the row's `title` (a ref naming one line by its index). */
    fun sendsTitle(target: DoneTarget): Boolean = when (target) {
        is DoneTarget.Todo -> true
        is DoneTarget.Reminder -> INDEXED_REF.matches(target.ref.trim())
        is DoneTarget.ActionItem -> false
    }

    /**
     * The JSON body of a done or undo request: `ref` or `id`, and the device id. [title], the row's
     * text as shown, goes with a done of a line by index ([sendsTitle]), so a hub whose line moved
     * since ticks the right one (or answers 409 "changed").
     */
    fun body(target: DoneTarget, deviceId: String, title: String? = null): String = buildJsonObject {
        when (target) {
            is DoneTarget.Todo -> put("ref", target.ref)
            is DoneTarget.Reminder -> put("ref", target.ref)
            is DoneTarget.ActionItem -> put("id", target.id)
        }
        put("device_id", deviceId)
        if (sendsTitle(target)) title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
    }.toString()

    /** The `error` string of an error answer, lower case, or null. */
    private fun error(body: String): String? =
        (obj(body)?.get("error") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()

    private fun obj(body: String): JsonObject? =
        runCatching { HubJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /**
     * What the hub's answer means. [body] is the answer's body, also for errors (the start of it):
     * 409 and 503 are told apart by their `error`. The reasons are short: they replace a row's due tag.
     */
    fun classify(code: Int, body: String): DoneOutcome = when (code) {
        in 200..299 -> {
            val o = obj(body)
            val ok = (o?.get("ok") as? JsonPrimitive)?.booleanOrNull
            if (ok == false) {
                DoneOutcome.Failed(DoneOutcome.Kind.OTHER, "Odysseus said no")
            } else {
                DoneOutcome.Ok(
                    already = (o?.get("already") as? JsonPrimitive)?.booleanOrNull ?: false,
                    title = (o?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                )
            }
        }
        400 -> DoneOutcome.Failed(DoneOutcome.Kind.BAD_REQUEST, "Odysseus refused it (400)")
        401, 403 -> DoneOutcome.Failed(DoneOutcome.Kind.REFUSED, hubStatusText(code))
        404 -> DoneOutcome.Failed(DoneOutcome.Kind.NOT_FOUND, "Not found in Odysseus")
        // "changed": the line was edited or removed in Odysseus; any other 409 is the undo's.
        409 -> if (error(body) == "changed") {
            DoneOutcome.Failed(DoneOutcome.Kind.CHANGED, "Changed in Odysseus")
        } else {
            DoneOutcome.Failed(DoneOutcome.Kind.TOO_LATE, "Too late to undo")
        }
        503 -> when {
            error(body)?.contains("athena") == true ->
                DoneOutcome.Failed(DoneOutcome.Kind.ATHENA_UNAVAILABLE, "Athena unavailable")
            error(body) == "busy" -> DoneOutcome.Failed(DoneOutcome.Kind.BUSY, "Odysseus busy, try again")
            else -> DoneOutcome.Failed(DoneOutcome.Kind.OTHER, "Odysseus busy (503)")
        }
        else -> DoneOutcome.Failed(DoneOutcome.Kind.OTHER, "Odysseus error ($code)")
    }

    /** The request never got an answer (not linked, network down, timeout). */
    fun unreachable(): DoneOutcome.Failed = DoneOutcome.Failed(DoneOutcome.Kind.UNREACHABLE, "Can't reach Odysseus")
}
