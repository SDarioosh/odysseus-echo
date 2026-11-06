package com.msp1974.vacompanion.odysseus.announce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementFeedTest {

    private class MemoryStore : AnnouncementFeedStore {
        override var since: Long? = null
        override var seen: Map<String, Long> = emptyMap()
    }

    private val now = 1_762_434_000_000L

    private fun a(id: String, at: Long = now - 1_000, expiresAt: Long? = null, kind: String = "announcement") =
        Announcement(id = id, at = at, kind = kind, title = "Fictional $id", text = "Text $id", expiresAt = expiresAt)

    @Test
    fun firstStartLooksBackAQuarterHour() {
        val feed = AnnouncementFeed(MemoryStore())
        assertEquals(now - AnnouncementFeed.FIRST_LOOKBACK_MS, feed.sinceFor(now))
    }

    @Test
    fun newOnesAreShownOldestFirst() {
        val feed = AnnouncementFeed(MemoryStore())
        val shown = feed.accept(listOf(a("an_2", at = now - 1_000), a("an_1", at = now - 5_000)), now)
        assertEquals(listOf("an_1", "an_2"), shown.map { it.id })
    }

    @Test
    fun eachIdIsShownOnce() {
        val feed = AnnouncementFeed(MemoryStore())
        assertEquals(1, feed.accept(listOf(a("an_1")), now).size)
        // The hub answers with it again (since is inclusive, or a retry): not shown again.
        assertTrue(feed.accept(listOf(a("an_1")), now + 30_000).isEmpty())
        assertEquals(listOf("an_2"), feed.accept(listOf(a("an_1"), a("an_2", at = now + 10_000)), now + 60_000).map { it.id })
    }

    @Test
    fun sinceIsTheNewestAtSeenAndPersisted() {
        val store = MemoryStore()
        AnnouncementFeed(store).accept(listOf(a("an_1", at = now - 9_000), a("an_2", at = now - 2_000)), now)
        assertEquals(now - 2_000, store.since)
        // A new feed on the same store (an app restart) asks from there and still knows the ids.
        val restarted = AnnouncementFeed(store)
        assertEquals(now - 2_000, restarted.sinceFor(now + 3_600_000))
        assertTrue(restarted.accept(listOf(a("an_2", at = now - 2_000)), now + 3_600_000).isEmpty())
    }

    @Test
    fun anEmptyAnswerKeepsSince() {
        val store = MemoryStore()
        val feed = AnnouncementFeed(store)
        feed.accept(listOf(a("an_1", at = now - 2_000)), now)
        feed.accept(emptyList(), now + 30_000)
        assertEquals(now - 2_000, store.since)
        assertNull(MemoryStore().since)
    }

    @Test
    fun expiredOnesAreSkippedButSeenAndAdvanceSince() {
        val store = MemoryStore()
        val feed = AnnouncementFeed(store)
        val shown = feed.accept(listOf(a("an_1", at = now - 60_000, expiresAt = now - 1)), now)
        assertTrue(shown.isEmpty())
        assertEquals(now - 60_000, store.since)
        assertTrue(store.seen.containsKey("an_1"))
    }

    @Test
    fun notYetExpiredIsShownEvenWhenOld() {
        val feed = AnnouncementFeed(MemoryStore())
        val old = a("an_1", at = now - 5 * 3_600_000L, expiresAt = now + 60_000)
        assertEquals(1, feed.accept(listOf(old), now).size)
    }

    @Test
    fun staleOnesWithoutExpiryAreSkipped() {
        val feed = AnnouncementFeed(MemoryStore())
        val stale = a("an_1", at = now - AnnouncementFeed.STALE_MS - 1)
        assertTrue(feed.accept(listOf(stale), now).isEmpty())
    }

    @Test
    fun idsOlderThanTheHubKeepsAreForgotten() {
        val store = MemoryStore()
        val feed = AnnouncementFeed(store)
        feed.accept(listOf(a("an_old", at = now - 1_000)), now)
        val later = now + AnnouncementFeed.KEEP_SEEN_MS + 10_000
        feed.accept(listOf(a("an_new", at = later)), later)
        assertEquals(setOf("an_new"), store.seen.keys)
    }

    @Test
    fun blankIdsAreIgnored() {
        val feed = AnnouncementFeed(MemoryStore())
        assertTrue(feed.accept(listOf(a("")), now).isEmpty())
    }

    @Test
    fun aSettingsItemIsNoCardButAsksForTheSettings() {
        val feed = AnnouncementFeed(MemoryStore())
        val news = feed.acceptNews(listOf(a("an_1"), a("st_1", kind = "settings")), now)
        assertEquals(listOf("an_1"), news.cards.map { it.id })
        assertTrue(news.settingsChanged)
        // Seen once: the same item again asks for nothing.
        assertEquals(AnnouncementFeed.News(emptyList(), false), feed.acceptNews(listOf(a("st_1", kind = "settings")), now + 30_000))
    }

    @Test
    fun anOldSettingsItemStillAsksForTheSettings() {
        val feed = AnnouncementFeed(MemoryStore())
        val news = feed.acceptNews(listOf(a("st_1", at = now - 3 * 3_600_000L, kind = "settings")), now)
        assertTrue(news.cards.isEmpty())
        assertTrue(news.settingsChanged)
    }

    @Test
    fun noSettingsItemNoRequest() {
        val feed = AnnouncementFeed(MemoryStore())
        assertEquals(false, feed.acceptNews(listOf(a("an_1")), now).settingsChanged)
    }
}
