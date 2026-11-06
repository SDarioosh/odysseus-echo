package com.msp1974.vacompanion.odysseus.announce

// Odysseus fork: the plain rules behind the announcement card and the polling (unit-tested):
// which cards are up and for how long, when the card must stay out of a voice turn's way, and how
// long to wait before the next request.

import com.msp1974.vacompanion.odysseus.VoiceActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One card waiting on screen. [untilMs] is on the device's clock. */
data class AnnouncementCardItem(val announcement: Announcement, val untilMs: Long)

/** The cards waiting to be seen, oldest first; the first one is shown. */
data class AnnouncementCards(val items: List<AnnouncementCardItem> = emptyList()) {
    val current: AnnouncementCardItem? get() = items.firstOrNull()
    val more: Int get() = (items.size - 1).coerceAtLeast(0)

    /** New announcements arrived: each stays SHOW_MS from now, or until it expires if sooner. */
    fun add(new: List<Announcement>, nowMs: Long): AnnouncementCards {
        val known = items.map { it.announcement.id }.toSet()
        val added = new.filter { it.id !in known && !it.isSettings }.map { a ->
            val until = nowMs + SHOW_MS
            AnnouncementCardItem(a, a.expiresAt?.let { minOf(it, until) } ?: until)
        }
        return copy(items = (items + added).takeLast(MAX_CARDS))
    }

    fun dismiss(id: String): AnnouncementCards = copy(items = items.filterNot { it.announcement.id == id })

    fun prune(nowMs: Long): AnnouncementCards = copy(items = items.filter { it.untilMs > nowMs })

    /** When the next card runs out, or null with none up. */
    fun nextDeadline(): Long? = items.minOfOrNull { it.untilMs }

    companion object {
        const val SHOW_MS = 10 * 60_000L
        /** More than this many at once is a flood; the oldest go. */
        const val MAX_CARDS = 10

        /** "3:05 PM", or "Tue 3:05 PM" when it wasn't today. */
        fun timeLabel(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
            val at = Instant.ofEpochMilli(atMs).atZone(zone)
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val time = DateTimeFormatter.ofPattern("h:mm a", locale).format(at)
            return if (at.toLocalDate() == now.toLocalDate()) time
            else DateTimeFormatter.ofPattern("EEE", locale).format(at) + " " + time
        }
    }
}

/**
 * Keeps the card out of a voice turn's way. A turn is "active" from its wake word (or follow-up)
 * until the voice indicator is idle again, and while the turn card or its page is up. An
 * announcement Home Assistant speaks also passes "listening" and "answering", but no wake word
 * started it since the indicator last went idle, so the card shows while it is spoken.
 */
object VoiceTurnGuard {
    /**
     * [idleSinceMs]: when the indicator last went idle; [turnStartedAtMs]: when the latest wake
     * word was heard (VoiceTurns.turnStartedAt).
     */
    fun turnActive(phase: VoiceActivity.Phase, idleSinceMs: Long, turnStartedAtMs: Long, turnCardUp: Boolean): Boolean =
        turnCardUp || (phase != VoiceActivity.Phase.IDLE && turnStartedAtMs > idleSinceMs)
}

/** How long to wait before the next feed request. */
object AnnouncementPolling {
    const val POLL_MS = 30_000L
    const val MAX_BACKOFF_MS = 5 * 60_000L
    /** Token refused: try again now and then; a relink also starts at once (link change). */
    const val UNAUTHORIZED_MS = 15 * 60_000L
    /** An older hub without the feed. */
    const val NOT_SUPPORTED_MS = 60 * 60_000L
    /** Not linked to Odysseus: no requests; only look at the settings again. */
    const val NOT_LINKED_MS = 60_000L
    const val HELLO_EVERY_MS = 60 * 60_000L

    enum class Outcome { OK, FAILED, UNAUTHORIZED, NOT_SUPPORTED, NOT_LINKED }

    /** [failures]: failed requests in a row, counting this one. */
    fun nextDelay(outcome: Outcome, failures: Int): Long = when (outcome) {
        Outcome.OK -> POLL_MS
        Outcome.FAILED -> {
            val n = (failures - 1).coerceIn(0, 10)
            minOf(POLL_MS shl n, MAX_BACKOFF_MS)
        }
        Outcome.UNAUTHORIZED -> UNAUTHORIZED_MS
        Outcome.NOT_SUPPORTED -> NOT_SUPPORTED_MS
        Outcome.NOT_LINKED -> NOT_LINKED_MS
    }

    /** A hello is due at start, hourly, and whenever what it says (or the hub) changed. */
    fun helloDue(lastHelloMs: Long?, lastSent: String?, nowSaying: String, nowMs: Long): Boolean =
        lastHelloMs == null || lastSent != nowSaying || nowMs - lastHelloMs >= HELLO_EVERY_MS
}
