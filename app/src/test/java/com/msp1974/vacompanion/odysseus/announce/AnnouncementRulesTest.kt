package com.msp1974.vacompanion.odysseus.announce

import com.msp1974.vacompanion.odysseus.VoiceActivity.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class AnnouncementRulesTest {
    private val now = 1_762_434_000_000L

    private fun a(id: String, expiresAt: Long? = null) = Announcement(id = id, at = now, title = "Fictional $id", expiresAt = expiresAt)

    // --- cards ---

    @Test
    fun cardsQueueOldestFirstAndCountTheRest() {
        val cards = AnnouncementCards().add(listOf(a("an_1"), a("an_2")), now).add(listOf(a("an_3")), now + 1)
        assertEquals("an_1", cards.current?.announcement?.id)
        assertEquals(2, cards.more)
        assertEquals("an_2", cards.dismiss("an_1").current?.announcement?.id)
    }

    @Test
    fun aCardStaysTenMinutesOrUntilItExpires() {
        val cards = AnnouncementCards().add(listOf(a("an_1"), a("an_2", expiresAt = now + 60_000)), now)
        assertEquals(now + AnnouncementCards.SHOW_MS, cards.items[0].untilMs)
        assertEquals(now + 60_000, cards.items[1].untilMs)
        assertEquals(now + 60_000, cards.nextDeadline())
        assertEquals(listOf("an_1"), cards.prune(now + 60_000).items.map { it.announcement.id })
        assertNull(cards.prune(now + AnnouncementCards.SHOW_MS).current)
        assertNull(AnnouncementCards().nextDeadline())
    }

    @Test
    fun theSameIdIsNotQueuedTwice() {
        val cards = AnnouncementCards().add(listOf(a("an_1")), now).add(listOf(a("an_1")), now + 5)
        assertEquals(1, cards.items.size)
    }

    @Test
    fun aFloodKeepsTheNewest() {
        val many = (1..15).map { a("an_$it") }
        val cards = AnnouncementCards().add(many, now)
        assertEquals(AnnouncementCards.MAX_CARDS, cards.items.size)
        assertEquals("an_6", cards.current?.announcement?.id)
    }

    @Test
    fun timeLabel() {
        val zone = ZoneId.of("America/New_York")
        // 2025-11-06T13:00:00Z = 8:00 AM in New York.
        val at = 1_762_434_000_000L
        assertEquals("8:00 AM", AnnouncementCards.timeLabel(at, at + 3_600_000, zone, Locale.US))
        assertEquals("Thu 8:00 AM", AnnouncementCards.timeLabel(at, at + 24 * 3_600_000L, zone, Locale.US))
    }

    // --- voice turn guard ---

    @Test
    fun hiddenDuringAWakeWordTurn() {
        val idleSince = now - 60_000
        val wake = now
        for (p in listOf(Phase.LISTENING, Phase.HEARING, Phase.THINKING, Phase.ANSWERING)) {
            assertTrue(p.name, VoiceTurnGuard.turnActive(p, idleSince, wake, turnCardUp = false))
        }
        assertFalse(VoiceTurnGuard.turnActive(Phase.IDLE, idleSince, wake, turnCardUp = false))
    }

    @Test
    fun shownWhileHaSpeaksTheAnnouncement() {
        // The last wake word was before the indicator went idle: this run has no wake word.
        val lastWake = now - 120_000
        val idleSince = now - 60_000
        assertFalse(VoiceTurnGuard.turnActive(Phase.ANSWERING, idleSince, lastWake, turnCardUp = false))
        assertFalse(VoiceTurnGuard.turnActive(Phase.LISTENING, idleSince, lastWake, turnCardUp = false))
    }

    @Test
    fun hiddenWhileTheTurnCardIsUp() {
        assertTrue(VoiceTurnGuard.turnActive(Phase.IDLE, now, 0, turnCardUp = true))
    }

    // --- polling ---

    @Test
    fun pollingDelays() {
        assertEquals(30_000L, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.OK, 0))
        assertEquals(30_000L, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.FAILED, 1))
        assertEquals(60_000L, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.FAILED, 2))
        assertEquals(120_000L, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.FAILED, 3))
        assertEquals(AnnouncementPolling.MAX_BACKOFF_MS, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.FAILED, 50))
        assertEquals(AnnouncementPolling.UNAUTHORIZED_MS, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.UNAUTHORIZED, 0))
        assertEquals(AnnouncementPolling.NOT_SUPPORTED_MS, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.NOT_SUPPORTED, 0))
        assertEquals(AnnouncementPolling.NOT_LINKED_MS, AnnouncementPolling.nextDelay(AnnouncementPolling.Outcome.NOT_LINKED, 0))
    }

    @Test
    fun helloAtStartHourlyAndOnChange() {
        assertTrue(AnnouncementPolling.helloDue(null, null, "x", now))
        assertFalse(AnnouncementPolling.helloDue(now, "x", "x", now + 59 * 60_000L))
        assertTrue(AnnouncementPolling.helloDue(now, "x", "x", now + 60 * 60_000L))
        assertTrue(AnnouncementPolling.helloDue(now, "x", "kitchen", now + 1_000))
    }

    @Test
    fun aSettingsItemNeverBecomesACard() {
        val settings = Announcement(id = "st_1", at = now, kind = Announcement.KIND_SETTINGS)
        assertTrue(AnnouncementCards().add(listOf(settings), now).items.isEmpty())
    }

    @Test
    fun aNewTokenIsALinkChange() {
        val before = AnnouncementPolling.linkSignature("https://odysseus.example/api/chat/voice/v1", 1234)
        // The token refused (401) is replaced in Menu → Odysseus: same hub, new token.
        val after = AnnouncementPolling.linkSignature("https://odysseus.example/api/chat/voice/v1", 5678)
        assertTrue(before != after)
        // ...so the hello (and with it the managed settings) is sent again at once.
        assertTrue(AnnouncementPolling.helloDue(now - 60_000, "$before|x", "$after|x", now))
    }

    @Test
    fun screenOnPollsOnlyWhenItHelpsAndNotInAStorm() {
        assertTrue(AnnouncementPolling.wakeHonored(AnnouncementPolling.Outcome.OK, 20_000))
        assertTrue(AnnouncementPolling.wakeHonored(AnnouncementPolling.Outcome.FAILED, 20_000))
        // The screen going on and off every few seconds doesn't poll every few seconds.
        assertFalse(AnnouncementPolling.wakeHonored(AnnouncementPolling.Outcome.OK, 2_000))
        // A refused token, an older hub, no link: their long waits hold.
        assertFalse(AnnouncementPolling.wakeHonored(AnnouncementPolling.Outcome.UNAUTHORIZED, 60_000))
        assertFalse(AnnouncementPolling.wakeHonored(AnnouncementPolling.Outcome.NOT_SUPPORTED, 60_000))
        assertFalse(AnnouncementPolling.wakeHonored(AnnouncementPolling.Outcome.NOT_LINKED, 60_000))
    }
}
