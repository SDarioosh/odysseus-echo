package com.msp1974.vacompanion.odysseus.home.today

import com.msp1974.vacompanion.odysseus.HubCall
import com.msp1974.vacompanion.odysseus.HubHttp
import com.msp1974.vacompanion.odysseus.HubReply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TodayClientTest {

    /** Contract C1's example (fictional data). */
    private val c1 = """
        {
          "date": "2026-11-06",
          "generated": "2026-11-06T07:45:00-05:00",
          "headline": "Two meetings this afternoon; the boiler service is due.",
          "focus":     [{"ref": "todo:41:2", "title": "Call the plumber", "why": "Due today"}],
          "events":    [{"uid": "abc", "title": "Dentist", "start": "2026-11-06T15:00:00-05:00",
                         "end": "2026-11-06T15:30:00-05:00", "all_day": false, "location": "Main St"}],
          "todos":     [{"ref": "todo:41:2", "title": "Call the plumber", "due": "2026-11-06", "list": "Home"}],
          "action_items": [{"id": "ai_17", "title": "Send Sam the photos", "source": "athena"}],
          "counts":    {"todos_open": 9, "action_items_open": 4}
        }
    """.trimIndent()

    private class FakeHub(val answer: HubReply) : HubHttp {
        val paths = mutableListOf<String>()
        override suspend fun request(call: HubCall): HubReply {
            paths += call.path
            return answer
        }
    }

    @Test
    fun parsesTheContract() {
        val hub = FakeHub(HubReply(200, c1))
        val d = runBlocking { TodayClient(hub).fetch() }
        assertEquals(listOf("/today"), hub.paths)
        assertEquals("2026-11-06", d.date)
        assertEquals("Two meetings this afternoon; the boiler service is due.", d.headline)
        assertEquals(listOf(TodayFocus("todo:41:2", "Call the plumber", "Due today")), d.focus)
        assertEquals(
            listOf(TodayEvent("abc", "Dentist", "2026-11-06T15:00:00-05:00", "2026-11-06T15:30:00-05:00", false, "Main St")),
            d.events,
        )
        assertEquals(listOf(TodayTodo("todo:41:2", "Call the plumber", "2026-11-06", "Home")), d.todos)
        assertEquals(listOf(TodayActionItem("ai_17", "Send Sam the photos", "athena")), d.actionItems)
        assertEquals(TodayCounts(9, 4), d.counts)
    }

    @Test
    fun emptySectionsAndNoInsightYet() {
        val d = TodayClient.parse(
            """{"date":"2026-11-06","headline":null,"focus":[],"events":[],"todos":[],"action_items":[],
               "counts":{"todos_open":0,"action_items_open":0}}"""
        )!!
        assertNull(d.headline)
        assertTrue(d.focus.isEmpty() && d.events.isEmpty() && d.todos.isEmpty() && d.actionItems.isEmpty())
        assertEquals(TodayCounts(0, 0), d.counts)
    }

    @Test
    fun missingOrNullSectionsAreEmptyAndUnknownKeysIgnored() {
        val d = TodayClient.parse("""{"date":"2026-11-06","events":null,"weather":{"x":1},"counts":null}""")!!
        assertTrue(d.events.isEmpty() && d.todos.isEmpty() && d.actionItems.isEmpty())
        assertEquals(TodayCounts(), d.counts)
    }

    @Test
    fun oneBadItemIsSkippedNotTheWholePage() {
        val d = TodayClient.parse(
            """{"date":"2026-11-06","events":[{"title":"Ok","start":"2026-11-06T09:00:00Z","all_day":"maybe"},
               {"title":"Lunch","start":"2026-11-06T12:00:00Z"},"junk",{"title":""}],
               "todos":[{"title":"Bins","due":null,"extra":true}]}"""
        )!!
        assertEquals(listOf("Lunch"), d.events.map { it.title })
        assertEquals(listOf(TodayTodo(title = "Bins")), d.todos)
    }

    @Test
    fun blankHeadlineIsNone() {
        assertNull(TodayClient.parse("""{"headline":"  "}""")!!.headline)
    }

    @Test
    fun notJsonIsNull() {
        assertNull(TodayClient.parse("<html>"))
        assertNull(TodayClient.parse("[1,2]"))
    }

    @Test
    fun httpErrorsThrowWithTheCode() {
        for (code in listOf(401, 403, 404, 500)) {
            try {
                runBlocking { TodayClient(FakeHub(HubReply(code, ""))).fetch() }
                fail("no exception for $code")
            } catch (e: TodayException) {
                assertEquals(code, e.code)
            }
        }
        try {
            runBlocking { TodayClient(FakeHub(HubReply(200, "oops"))).fetch() }
            fail("no exception for a bad body")
        } catch (e: TodayException) {
            assertEquals(0, e.code)
        }
    }
}
