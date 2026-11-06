package com.msp1974.vacompanion.odysseus.announce

import com.msp1974.vacompanion.odysseus.HubFixtures
import com.msp1974.vacompanion.odysseus.done.DoneApi
import com.msp1974.vacompanion.odysseus.done.DoneTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract C3 (and C6's "settings" items) against the hub's real feed answers (HubFixtures). */
class AnnouncementApiHubFixturesTest {

    private class MemoryStore : AnnouncementFeedStore {
        override var since: Long? = null
        override var seen: Map<String, Long> = emptyMap()
    }

    // A reminder from the hub's reminder scanner, two test announcements to this device, one
    // to its area (as Home Assistant names it: "kitchen"; the device said "Kitchen"), and two
    // "settings" items from two settings changes.
    private val items = AnnouncementApi.parseFeed(HubFixtures.load("c3_announcements.json"))

    @Test
    fun everyKindAndTarget() {
        assertEquals(listOf("reminder", "announcement", "announcement", "announcement", "settings", "settings"), items.map { it.kind })

        val reminder = items[0]
        assertTrue(reminder.isReminder)
        assertTrue(reminder.canMarkDone)
        assertEquals("Feed the fictional cat", reminder.heading)
        assertEquals("Reminder: Feed the fictional cat", reminder.body)
        assertEquals(1793938123706L, reminder.at)
        assertEquals(1793941723706L, reminder.expiresAt)
        // "all" comes as an object, like the other targets.
        assertTrue(((reminder.target as JsonObject)["all"] as JsonPrimitive).boolean)
        // Its ref names the whole note: the card's Done goes to reminders/done.
        assertEquals("todo:4ef56451-1b45-43c8-934d-eb6fb1ff87a1", reminder.ref)
        assertEquals(
            """{"ref":"todo:4ef56451-1b45-43c8-934d-eb6fb1ff87a1","device_id":"echo-kitchen-1"}""",
            DoneApi.body(DoneTarget.Reminder(reminder.ref!!), "echo-kitchen-1"),
        )

        val test = items[1]
        assertFalse(test.isReminder || test.isSettings || test.canMarkDone)
        assertNull(test.ref)
        assertEquals("Test announcement", test.heading)
        assertEquals("Fictional test for the kitchen.", test.body)
        assertEquals(JsonPrimitive("echo-kitchen-1"), (test.target as JsonObject)["device_id"])

        val area = items[3]
        assertEquals(JsonPrimitive("kitchen"), (area.target as JsonObject)["area"])
        assertNull(area.body) // the text only repeats the title

        val settings = items[4]
        assertTrue(settings.isSettings)
        assertFalse(settings.canMarkDone)
        assertEquals("settings:1", settings.ref)
    }

    @Test
    fun theFeedShowsCardsOnceAndAsksForSettings() {
        val feed = AnnouncementFeed(MemoryStore())
        val now = 1793938400000L // after the last item, before any expires
        val news = feed.acceptNews(items, now)
        assertEquals(listOf("an_7a62d07065f94420", "an_e16d5fea79324638", "an_27be5df6099c4bdf", "an_30d9a381715c44d3"), news.cards.map { it.id })
        assertTrue(news.settingsChanged)
        assertEquals(1793938355168L, feed.sinceFor(now)) // the newest `at`, the hub's clock

        val again = feed.acceptNews(items, now + 30_000)
        assertTrue(again.cards.isEmpty())
        assertFalse(again.settingsChanged)
    }

    @Test
    fun expiredItemsMakeNoCardButSettingsStillCount() {
        val news = AnnouncementFeed(MemoryStore()).acceptNews(items, 1793942000000L) // after every expires_at
        assertTrue(news.cards.isEmpty())
        assertTrue(news.settingsChanged)
    }

    @Test
    fun helloAndAnEmptyFeed() {
        assertTrue(AnnouncementApi.parseHello(HubFixtures.load("c3_hello.json")))
        assertTrue(AnnouncementApi.parseFeed("""{"announcements": []}""").isEmpty())
    }
}
