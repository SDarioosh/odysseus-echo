package com.msp1974.vacompanion.odysseus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnApiTest {

    // Fictional data in the shape of voice contract v1 §1.
    private val latest = """
        {"turn": {
          "id": "t1", "started_at": "2026-11-03T19:52:00+00:00", "ended_at": "2026-11-03T19:52:09+00:00",
          "question": "What did Alice say about the budget?",
          "answer": "She said it is fine.",
          "links": [
            {"url": "https://athena.example.lan/view/12?sig=abc#t=34", "label": "Budget review"},
            {"url": "https://athena.example.lan/view/12?sig=abc#t=34", "label": "duplicate"},
            {"url": "javascript:alert(1)", "label": "bad"},
            {"url": "http://www.example.org/page", "label": null},
            {"url": "https://example.net/a", "label": "  "},
            {"url": "https://example.com/4", "label": "fourth"}
          ],
          "actions": [{"id": "a1", "at": "2026-11-03T19:52:10+00:00", "action": "created", "kind": "event",
                       "title": "Dentist", "detail": null, "due": "2026-11-04T15:00:00-05:00", "all_day": false,
                       "where": "Calendar", "source": "odysseus", "turn_id": "t1", "new_field": 1}],
          "awaiting_confirmation": null,
          "device": {"id": "dev", "name": "Kitchen Echo", "area": "Kitchen"},
          "speaker": {"verified": true, "is_owner": true, "name": "Sam"},
          "session_id": "chat-1",
          "something_new": {"x": 1}
        }}
    """.trimIndent()

    @Test
    fun parsesTheContractShapeAndIgnoresUnknownFields() {
        val t = VoiceTurnApi.parseLatestTurn(latest)!!
        assertEquals("t1", t.id)
        assertEquals("She said it is fine.", t.answer)
        assertEquals(6, t.links.size)
        assertEquals("Dentist", t.actions.single().title)
        assertEquals("Kitchen", t.device?.area)
        assertTrue(t.speaker!!.isOwner)
        assertNull(t.awaitingConfirmation)
    }

    @Test
    fun nullTurn() {
        assertNull(VoiceTurnApi.parseLatestTurn("""{"turn": null}"""))
        assertNull(VoiceTurnApi.parseLatestTurn("""{}"""))
    }

    @Test
    fun linksAreWebOnlyUniqueAndAtMostThree() {
        val links = VoiceTurnApi.displayLinks(VoiceTurnApi.parseLatestTurn(latest)!!.links)
        assertEquals(
            listOf("https://athena.example.lan/view/12?sig=abc#t=34", "http://www.example.org/page", "https://example.net/a"),
            links.map { it.url },
        )
        assertEquals("Budget review", VoiceTurnApi.linkLabel(links[0]))
        assertEquals("example.org", VoiceTurnApi.linkLabel(links[1]))
        assertEquals("example.net", VoiceTurnApi.linkLabel(links[2]))
    }

    @Test
    fun cardForATurnWithLinksShowsTheAnswer() {
        val card = VoiceTurnApi.cardState(VoiceTurnApi.parseLatestTurn(latest)!!)!!
        assertEquals("t1", card.turnId)
        assertEquals(3, card.links.size)
        assertEquals("She said it is fine.", card.answer)
        assertNull(card.awaitingConfirmation)
        assertEquals(2, VoiceTurnApi.actionRows(card))
    }

    @Test
    fun awaitingConfirmationAloneIsWorthACard() {
        val t = VoiceTurn(id = "t2", answer = "Add Dentist on Tuesday at 3 PM to your calendar?",
            awaitingConfirmation = "Add Dentist on Tuesday at 3 PM to your calendar?")
        val card = VoiceTurnApi.cardState(t)!!
        assertEquals("Add Dentist on Tuesday at 3 PM to your calendar?", card.awaitingConfirmation)
        assertNull(card.answer) // the answer only shows above links
    }

    @Test
    fun aTurnThatDidNothingHasNoCard() {
        assertNull(VoiceTurnApi.cardState(VoiceTurn(id = "t3", answer = "It's 5 PM.", awaitingConfirmation = " ")))
        assertNull(VoiceTurnApi.cardState(emptyList()))
        assertNotNull(VoiceTurnApi.cardState(listOf(VoiceAction(id = "a", title = "Milk"))))
    }

    @Test
    fun webUrls() {
        assertTrue(VoiceTurnApi.isWebUrl("https://h/x"))
        assertFalse(VoiceTurnApi.isWebUrl("file:///sdcard/x"))
        assertFalse(VoiceTurnApi.isWebUrl("intent://x"))
        assertFalse(VoiceTurnApi.isWebUrl("not a url"))
        assertFalse(VoiceTurnApi.isWebUrl("https:///nohost"))
    }

    @Test
    fun satelliteResponse() {
        val r = VoiceTurnApi.parseSatelliteTurnResponse(
            """{"ok": true, "context_id": "c1", "speaker": {"verified": true, "is_owner": false, "name": null, "score": 0.42}}"""
        )
        assertTrue(r.ok)
        assertEquals("c1", r.contextId)
        assertEquals(0.42, r.speaker!!.score!!, 1e-9)
        assertNull(VoiceTurnApi.parseSatelliteTurnResponse("""{"ok": true, "context_id": "c2", "speaker": null}""").speaker)
    }

    @Test
    fun multipartHasEveryFieldAndTheAudio() {
        val form = SatelliteTurnForm("dev1", "Kitchen\r\nEcho", "", "hey_athena", 1_793_742_000_000L)
        val wav = byteArrayOf(82, 73, 70, 70, 0, 1, 2)
        val body = VoiceTurnApi.multipartBody("BOUND", form.fields(), wav)
        val text = String(body, Charsets.ISO_8859_1)
        for (name in listOf("device_id", "device_name", "area", "wake_word", "started_at")) {
            assertTrue(name, text.contains("Content-Disposition: form-data; name=\"$name\"\r\n\r\n"))
        }
        assertTrue(text.contains("name=\"device_name\"\r\n\r\nKitchen Echo\r\n"))
        assertTrue(text.contains("name=\"area\"\r\n\r\n\r\n"))
        assertTrue(text.contains("name=\"started_at\"\r\n\r\n1793742000000\r\n"))
        assertTrue(text.contains("name=\"audio\"; filename=\"command.wav\"\r\nContent-Type: audio/wav\r\n\r\nRIFF\u0000\u0001\u0002\r\n"))
        assertTrue(text.endsWith("--BOUND--\r\n"))
        assertFalse(String(VoiceTurnApi.multipartBody("B", form.fields(), null)).contains("audio"))
    }

    @Test
    fun turnsOfAnotherSatelliteAreNotShown() {
        val t = VoiceTurn(id = "t", device = TurnDevice(id = "other"))
        assertFalse(VoiceTurnApi.isFromDevice(t, "me"))
        assertTrue(VoiceTurnApi.isFromDevice(t, "other"))
        assertTrue(VoiceTurnApi.isFromDevice(VoiceTurn(id = "t"), "me"))
        assertTrue(VoiceTurnApi.isFromDevice(t, ""))
    }
}
