package com.msp1974.vacompanion.odysseus.home.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class TodayStoreTest {
    private val today: LocalDate = LocalDate.of(2026, 11, 6)
    private val data = TodayData(date = "2026-11-06", headline = "A quiet day")
    private val t0 = 1_000_000_000L
    private val hour = 60 * 60_000L

    @Test
    fun notLinkedWins() {
        val s = TodayStore()
        s.onSuccess(data, t0)
        assertEquals(TodayView.NotLinked, s.view(t0, today, linked = false))
    }

    @Test
    fun loadingThenUnreachableBeforeAnyData() {
        val s = TodayStore()
        assertEquals(TodayView.Loading, s.view(t0, today, true))
        s.onFailure("Can't reach Odysseus", t0)
        assertEquals(TodayView.Unreachable("Can't reach Odysseus"), s.view(t0, today, true))
    }

    @Test
    fun readyAfterSuccess() {
        val s = TodayStore()
        s.onSuccess(data, t0)
        assertEquals(TodayView.Ready(data, t0, stale = false), s.view(t0 + 1000, today, true))
    }

    @Test
    fun failureKeepsTheLastResultForTwoHoursMarkedStale() {
        val s = TodayStore()
        s.onSuccess(data, t0)
        s.onFailure("down", t0 + hour)
        assertEquals(TodayView.Ready(data, t0, stale = true, error = "down"), s.view(t0 + hour, today, true))
        assertEquals(TodayView.Ready(data, t0, stale = true, error = "down"), s.view(t0 + 2 * hour, today, true))
        assertEquals(TodayView.Unreachable("down"), s.view(t0 + 2 * hour + 1, today, true))
    }

    @Test
    fun aSuccessAfterAFailureIsFreshAgain() {
        val s = TodayStore()
        s.onSuccess(data, t0)
        s.onFailure("down", t0 + 1)
        s.onSuccess(data, t0 + 2)
        assertEquals(TodayView.Ready(data, t0 + 2, stale = false), s.view(t0 + 3, today, true))
    }

    @Test
    fun yesterdaysResultIsDroppedAfterMidnight() {
        val s = TodayStore()
        s.onSuccess(data, t0)
        assertEquals(TodayView.Loading, s.view(t0 + 1000, today.plusDays(1), true))
        // A hub ahead in time (or an unreadable date) is kept.
        assertFalse(TodayStore.isForEarlierDay(data, today.minusDays(1)))
        assertFalse(TodayStore.isForEarlierDay(data.copy(date = ""), today))
    }

    @Test
    fun freshnessForMinAge() {
        val s = TodayStore()
        assertFalse(s.isFresh(t0, 60_000))
        s.onSuccess(data, t0)
        assertTrue(s.isFresh(t0 + 59_000, 60_000))
        assertFalse(s.isFresh(t0 + 60_000, 60_000))
        assertFalse(s.isFresh(t0 + 1, 0))
        s.onFailure("down", t0 + 1)
        assertFalse(s.isFresh(t0 + 2, 60_000))
    }

    @Test
    fun clearForgetsEverything() {
        val s = TodayStore()
        s.onSuccess(data, t0)
        s.clear()
        assertEquals(TodayView.Loading, s.view(t0, today, true))
    }

    @Test
    fun todayIsTheHubsDayNotTheDevicesZone() {
        // 20:00 in New York on Nov 6 is already Nov 7 in UTC. A device left on UTC must not drop
        // the hub's current result (and show "Loading today…" all evening).
        val now = Instant.parse("2026-11-07T01:00:00Z").toEpochMilli()
        val hub = data.copy(generated = "2026-11-06T19:45:00-05:00")
        assertEquals(LocalDate.of(2026, 11, 6), TodayStore.today(hub, now, ZoneOffset.UTC))
        val s = TodayStore()
        s.onSuccess(hub, now)
        assertEquals(TodayView.Ready(hub, now, stale = false), s.view(now, TodayStore.today(hub, now, ZoneOffset.UTC), true))
        // Without `generated`, the device's zone.
        assertEquals(LocalDate.of(2026, 11, 7), TodayStore.today(data, now, ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 11, 6), TodayStore.today(null, now, ZoneId.of("America/New_York")))
    }

    @Test
    fun refreshIsDueAnIntervalAfterTheLastAttemptFailedOrNot() {
        val s = TodayStore()
        val interval = 15 * 60_000L
        assertTrue(s.refreshDue(t0, today, interval))
        s.onSuccess(data, t0)
        assertFalse(s.refreshDue(t0 + interval - 1, today, interval))
        assertTrue(s.refreshDue(t0 + interval, today, interval))
        // A failure counts as an attempt: a hub that is down isn't asked every minute.
        s.onFailure("down", t0 + interval)
        assertFalse(s.refreshDue(t0 + interval + 60_000, today, interval))
        assertTrue(s.refreshDue(t0 + 2 * interval, today, interval))
        // A clock that went backwards.
        assertTrue(s.refreshDue(t0 - 1, today, interval))
    }

    @Test
    fun refreshIsDueSoonAfterMidnight() {
        val s = TodayStore()
        val interval = 15 * 60_000L
        s.onSuccess(data, t0)
        val tomorrow = today.plusDays(1)
        assertFalse(s.refreshDue(t0 + 60_000, tomorrow, interval))
        assertTrue(s.refreshDue(t0 + TodayStore.ROLLOVER_RETRY_MS, tomorrow, interval))
        assertFalse(s.refreshDue(t0 + TodayStore.ROLLOVER_RETRY_MS, today, interval))
    }

    @Test
    fun withoutANetworkTheResultIsStaleNotCurrent() {
        // Offline nothing is fetched, so no failure is recorded: without this the page showed
        // the last result as current, for as long as the network was down.
        val s = TodayStore()
        s.onSuccess(data, t0)
        val v = s.view(t0 + hour, today, true)
        assertEquals(
            TodayView.Ready(data, t0, stale = true, error = TodayStore.OFFLINE_MESSAGE),
            TodayStore.offline(v),
        )
        // Still dropped after 2 h (TodayRefresh re-applies the rules every minute).
        assertEquals(TodayView.Unreachable(TodayStore.OFFLINE_MESSAGE), TodayStore.offline(s.view(t0 + 3 * hour, today, true)))
        assertEquals(TodayView.NotLinked, TodayStore.offline(TodayView.NotLinked))
        val failed = TodayView.Ready(data, t0, stale = true, error = "down")
        assertEquals(failed, TodayStore.offline(failed))
    }
}
