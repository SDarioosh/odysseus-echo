package com.msp1974.vacompanion.odysseus.announce

// Odysseus fork: "what's new" in the announcements feed (pure, unit-tested).
//
// - Each id is shown once: ids already seen are remembered (with their `at`) for as long as the
//   hub keeps announcements (24 h), and across restarts.
// - `since` for the next request is the newest `at` seen (the hub's clock, so the device's clock
//   doesn't matter), remembered across restarts. With nothing remembered (first start, or after
//   the data was cleared) it is FIRST_LOOKBACK_MS before now, so an Echo that starts doesn't
//   show a day of old announcements.
// - Expired ones (expires_at passed) are skipped but still marked seen. So are ones older than
//   STALE_MS that have no expires_at of their own: an Echo that was off for hours doesn't show
//   them all when it comes back.

/** Where the feed state is kept (SharedPreferences on the device, a map in tests). */
interface AnnouncementFeedStore {
    /** Newest `at` seen, or null when nothing was ever seen. */
    var since: Long?
    /** id → its `at`. */
    var seen: Map<String, Long>
}

class AnnouncementFeed(private val store: AnnouncementFeedStore) {

    /** The `since` to ask with now. */
    fun sinceFor(nowMs: Long): Long = store.since ?: (nowMs - FIRST_LOOKBACK_MS)

    /**
     * Takes a feed answer and returns the announcements to show, oldest first. Updates and
     * persists `since` and the seen ids.
     */
    fun accept(items: List<Announcement>, nowMs: Long): List<Announcement> {
        val seen = store.seen.toMutableMap()
        val fresh = mutableListOf<Announcement>()
        var newest = store.since
        for (a in items.sortedBy { it.at }) {
            if (a.id.isBlank()) continue
            if (newest == null || a.at > newest) newest = a.at
            if (seen.containsKey(a.id)) continue
            seen[a.id] = a.at
            if (isShowable(a, nowMs)) fresh += a
        }
        // Forget ids the hub no longer keeps, measured on the hub's clock (the newest `at`).
        val horizon = newest?.let { it - KEEP_SEEN_MS }
        if (horizon != null) seen.entries.removeAll { it.value < horizon }
        while (seen.size > MAX_SEEN) seen.remove(seen.minByOrNull { it.value }!!.key)
        store.seen = seen
        if (newest != null) store.since = newest
        return fresh
    }

    companion object {
        const val FIRST_LOOKBACK_MS = 15 * 60_000L
        /** The hub keeps announcements 24 h; keep their ids a little longer. */
        const val KEEP_SEEN_MS = 26 * 3_600_000L
        const val STALE_MS = 2 * 3_600_000L
        const val MAX_SEEN = 500

        fun isShowable(a: Announcement, nowMs: Long): Boolean {
            val expires = a.expiresAt
            if (expires != null) return expires > nowMs
            return nowMs - a.at < STALE_MS
        }
    }
}
