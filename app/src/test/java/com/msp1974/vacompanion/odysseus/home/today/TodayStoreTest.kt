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
}
