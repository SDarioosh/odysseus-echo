package com.msp1974.vacompanion.odysseus.alarms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class AlarmRulesTest {
    private val zone = ZoneId.of("America/New_York")

    // 2026-11-16 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    private fun weekdays(time: String = "07:30") = AlarmRules.of(HubAlarm("al_1", time, listOf(1, 2, 3, 4, 5)))!!

    @Test fun parsesTheHubList() {
        val list = AlarmRules.parse(
            """{"alarms": [
                {"id": "al_1", "time": "07:30", "days": [1,2,3,4,5], "date": null, "label": "Gym"},
                {"id": "al_2", "time": "06:00", "days": [], "date": "2026-11-17", "label": null, "extra": 1},
                {"id": "", "time": "06:00", "days": [1]},
                {"id": "al_3", "time": "25:00", "days": [1]},
                {"id": "al_4", "time": "08:00", "days": []}
            ], "updated_at": "2026-11-16T10:00:00Z"}""",
        )
        assertEquals(listOf("al_1", "al_2"), list.map { it.id })
        assertEquals("Gym", list[0].label)
        assertEquals(setOf(1, 2, 3, 4, 5), list[0].days)
        assertEquals(LocalDate.of(2026, 11, 17), list[1].date)
        assertEquals(LocalTime.of(6, 0), list[1].time)
    }

    @Test fun roundTripsThroughTheKeptCopy() {
        val list = AlarmRules.parse("""{"alarms": [{"id": "al_1", "time": "07:30", "days": [5,1], "label": " Gym "}]}""")
        assertEquals(list, AlarmRules.parse(AlarmRules.encode(list)))
    }

    @Test fun repeatingAlarmRingsOnTheNextMatchingDay() {
        val a = weekdays()
        assertEquals(at(16, 7, 30), AlarmRules.nextRing(a, at(16, 6, 0)))
        // Exactly at the ring time: the next one.
        assertEquals(at(17, 7, 30), AlarmRules.nextRing(a, at(16, 7, 30)))
        // Friday evening: Monday.
        assertEquals(at(23, 7, 30), AlarmRules.nextRing(a, at(20, 20, 0)))
    }

    @Test fun oneOffRingsOnceThenNever() {
        val a = AlarmRules.of(HubAlarm("al_2", "06:00", emptyList(), "2026-11-17"))!!
        assertEquals(at(17, 6, 0), AlarmRules.nextRing(a, at(16, 22, 0)))
        assertNull(AlarmRules.nextRing(a, at(17, 6, 0)))
    }

    @Test fun ringsAtWallClockTimeInTheDeviceZone() {
        val a = weekdays()
        val london = ZoneId.of("Europe/London")
        val next = AlarmRules.nextRing(a, at(16, 6, 0), london)!!
        assertEquals(LocalTime.of(7, 30), next.toLocalTime())
        assertEquals(london, next.zone)
    }

    @Test fun aTimeSkippedBySpringForwardStillRings() {
        // 2026-03-08 02:30 doesn't exist in New York.
        val a = AlarmRules.of(HubAlarm("al_5", "02:30", emptyList(), "2026-03-08"))!!
        val next = AlarmRules.nextRing(a, ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, zone))!!
        assertEquals(LocalDate.of(2026, 3, 8), next.toLocalDate())
    }

    @Test fun labels() {
        assertEquals("Weekdays", AlarmRules.daysLabel(setOf(1, 2, 3, 4, 5), Locale.US))
        assertEquals("Weekends", AlarmRules.daysLabel(setOf(6, 7), Locale.US))
        assertEquals("Every day", AlarmRules.daysLabel((1..7).toSet(), Locale.US))
        assertEquals("Mon, Wed", AlarmRules.daysLabel(setOf(3, 1), Locale.US))
        assertNull(AlarmRules.daysLabel(emptySet(), Locale.US))
        assertEquals("7:30 AM", AlarmRules.timeLabel(LocalTime.of(7, 30), Locale.US))
        val oneOff = AlarmRules.of(HubAlarm("al_2", "06:00", emptyList(), "2026-11-17"))!!
        assertEquals("Tomorrow", AlarmRules.whenLabel(oneOff, LocalDate.of(2026, 11, 16), Locale.US))
        assertEquals("Today", AlarmRules.whenLabel(oneOff, LocalDate.of(2026, 11, 17), Locale.US))
        assertEquals("Tue 17 Nov", AlarmRules.whenLabel(oneOff, LocalDate.of(2026, 11, 10), Locale.US))
    }

    @Test fun pathsAreEncoded() {
        assertEquals("/alarms?device_id=a+b", AlarmRules.listPath("a b"))
        assertTrue(AlarmRules.deletePath("al_1", "dev").startsWith("/alarms/al_1?device_id=dev"))
    }
}
