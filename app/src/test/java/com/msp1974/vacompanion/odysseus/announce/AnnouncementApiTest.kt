package com.msp1974.vacompanion.odysseus.announce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementApiTest {

    @Test
    fun parsesTheContractExample() {
        val body = """
            {"announcements": [
              {"id": "an_102", "at": 1762434000000, "kind": "reminder",
               "title": "Take the bins out", "text": "Reminder: take the bins out",
               "target": {"area": "kitchen"}, "ref": "todo:41:3", "expires_at": 1762437600000,
               "something_new": [1, 2]}
            ]}
        """.trimIndent()
        val a = AnnouncementApi.parseFeed(body).single()
        assertEquals("an_102", a.id)
        assertEquals(1762434000000L, a.at)
        assertTrue(a.isReminder)
        assertEquals("Take the bins out", a.heading)
        assertEquals("Reminder: take the bins out", a.body)
        assertEquals("todo:41:3", a.ref)
        assertEquals(1762437600000L, a.expiresAt)
    }

    @Test
    fun missingFieldsHaveDefaults() {
        val a = AnnouncementApi.parseFeed("""{"announcements": [{"id": "an_1", "text": "Dinner is ready", "expires_at": null, "target": "all"}]}""").single()
        assertFalse(a.isReminder)
        assertEquals("Dinner is ready", a.heading)
        assertNull(a.body) // doesn't repeat the heading
        assertNull(a.expiresAt)
    }

    @Test
    fun emptyAndIdlessAnswers() {
        assertTrue(AnnouncementApi.parseFeed("{}").isEmpty())
        assertTrue(AnnouncementApi.parseFeed("").isEmpty())
        assertTrue(AnnouncementApi.parseFeed("""{"announcements": [{"text": "no id"}]}""").isEmpty())
    }

    @Test
    fun feedPathEncodesTheDeviceId() {
        assertEquals("/announcements?device_id=echo+kitchen%2F1&since=42", AnnouncementApi.feedPath("echo kitchen/1", 42))
    }

    @Test
    fun helloFormFields() {
        val form = HelloForm.of("abc123", "  ", " Kitchen ", 1400031)
        assertEquals("VACA abc123", form.deviceName)
        assertEquals(
            "device_id=abc123&device_name=VACA+abc123&area=Kitchen&app_version_code=1400031",
            AnnouncementApi.formBody(form.fields()),
        )
        assertEquals("Kitchen Echo", HelloForm.of("abc123", "Kitchen Echo", "", 1).deviceName)
    }

    @Test
    fun helloAnswer() {
        assertTrue(AnnouncementApi.parseHello("""{"ok": true}"""))
        assertFalse(AnnouncementApi.parseHello("""{"ok": false}"""))
        assertFalse(AnnouncementApi.parseHello("not json"))
    }
}
