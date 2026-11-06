package com.msp1974.vacompanion.odysseus.home.today

import com.msp1974.vacompanion.odysseus.HaResponse
import com.msp1974.vacompanion.odysseus.HubFixtures
import com.msp1974.vacompanion.odysseus.done.DoneApi
import com.msp1974.vacompanion.odysseus.done.DoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Contract C1 against the hub's real GET /today answers (HubFixtures). */
class TodayHubFixturesTest {

    private fun parse(name: String): TodayData = TodayClient.parse(HubFixtures.load(name))!!

    @Test
    fun readsAFullDay() {
        val d = TodayClient(TodayHttp { HaResponse(200, HubFixtures.load("c1_today.json")) }).fetch()
        assertEquals("2026-11-06", d.date)
        assertEquals("The dentist at three; the plumber is due today.", d.headline)

        // The insight's focus names events (their uid) as well as to-dos.
        assertEquals(listOf("Call the plumber", "Dentist"), d.focus.map { it.title })
        assertTrue(d.focus[0].ref.startsWith("todo:"))
        assertEquals("205e6a2c-b081-4e7e-974e-1bc830e0c30d", d.focus[1].ref)

        assertEquals(listOf("Recycling day", "Standup", "Dentist"), d.events.map { it.title })
        val (allDay, standup, dentist) = d.events
        assertTrue(allDay.allDay)
        assertNull(allDay.location) // the hub sends null, not ""
        assertEquals("d4ca75a2-92aa-414d-9576-ac7d143784fa::2026-11-06T09:00", standup.uid)
        assertEquals("Main St", dentist.location)

        assertEquals(4, d.todos.size)
        assertEquals("todo:4eb50fe3-e5bf-425a-8504-deb1a249e0a4:0", d.todos[0].ref)
        assertEquals("Errands", d.todos[0].list)
        assertNull(d.todos[3].due) // a pinned note's item without a date
        assertEquals("Packing", d.todos[3].list)

        // Athena's ids are plain numbers as text ("1"), not the plan's "ai_17".
        assertEquals(listOf("1", "2", "3"), d.actionItems.map { it.id })
        assertEquals("athena", d.actionItems[0].source)
        assertEquals(4, d.counts.todosOpen)
        assertEquals(3, d.counts.actionItemsOpen)
    }

    @Test
    fun rowsGiveTheDoneBodiesTheHubAccepted() {
        // The bodies the sandbox run sent to todos/done and action-items/done (200 ok).
        val d = parse("c1_today.json")
        assertEquals(
            """{"ref":"todo:0ba57096-fbe2-4dd4-9715-b68334f36ffb:0","device_id":"echo-kitchen-1"}""",
            DoneApi.body(DoneTarget.Todo(d.todos[1].ref), "echo-kitchen-1"),
        )
        assertEquals("""{"id":"1","device_id":"echo-kitchen-1"}""", DoneApi.body(DoneTarget.ActionItem(d.actionItems[0].id), "echo-kitchen-1"))
    }

    @Test
    fun theHubsTimesShowInTheDevicesZone() {
        val d = parse("c1_today_offset.json") // the owner's zone is UTC+1 here
        assertEquals("2026-11-06T05:15:45+01:00", d.generated)
        val ny = ZoneId.of("America/New_York")
        val (allDay, standup, dentist) = d.events
        assertEquals("All day", TodayFormat.eventTime(allDay, ny, true, Locale.US))
        assertEquals("4:00", TodayFormat.eventTime(standup, ny, true, Locale.US))
        assertEquals("10:00 AM", TodayFormat.eventTime(dentist, ny, false, Locale.US))

        val at1030 = ZonedDateTime.of(2026, 11, 6, 10, 30, 0, 0, ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        assertTrue(TodayFormat.isPast(standup, at1030))
        assertFalse(TodayFormat.isPast(dentist, at1030))
        assertFalse(TodayFormat.isPast(allDay, at1030))
    }

    @Test
    fun dueLabelsOfTheHubsDates() {
        val d = parse("c1_today.json")
        val today = LocalDate.of(2026, 11, 6)
        assertEquals(listOf("Overdue", "Today", "Today", null), d.todos.map { TodayFormat.dueLabel(it, today) })
    }

    @Test
    fun anEmptyDayAndAthenaDown() {
        val empty = parse("c1_today_empty.json")
        assertNull(empty.headline)
        assertTrue(empty.focus.isEmpty() && empty.events.isEmpty() && empty.todos.isEmpty() && empty.actionItems.isEmpty())
        assertEquals(0, empty.counts.todosOpen)
        assertEquals(0, empty.counts.actionItemsOpen)

        val down = parse("c1_today_athena_down.json")
        assertTrue(down.actionItems.isEmpty())
        assertEquals(0, down.counts.actionItemsOpen)
        assertEquals(3, down.events.size)
    }
}
