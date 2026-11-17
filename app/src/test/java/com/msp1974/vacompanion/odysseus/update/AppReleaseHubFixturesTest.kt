package com.msp1974.vacompanion.odysseus.update

import com.msp1974.vacompanion.odysseus.HubFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract C2 against the hub's real GET /app/latest answers (HubFixtures). */
class AppReleaseHubFixturesTest {

    private class Hub(val code: Int, val text: String) : UpdateHttp {
        override fun getText(path: String) = UpdateResponse(code, text)
        override fun getStream(path: String, rangeFrom: Long) = UpdateResponse(500)
    }

    @Test
    fun aPublishedRelease() {
        val r = AppRelease.parse(HubFixtures.load("c2_app_latest.json"))
        assertNull(r.problem())
        assertEquals("0.14.0+odysseus.30", r.versionName)
        assertEquals(1400030L, r.versionCode)
        assertEquals(5246L, r.size)
        assertEquals("0cb5820ca3d7d768fe0acf621963103b522dd7eabf9ae2aae5ab47c8198eaa5f", r.sha)
        assertEquals("Fictional test build.", r.notes)
        assertEquals(CheckResult.Found(r), UpdateClient(Hub(200, HubFixtures.load("c2_app_latest.json"))).latest())
    }

    @Test
    fun noRelease() {
        assertEquals(CheckResult.NoRelease, UpdateClient(Hub(404, HubFixtures.load("c2_app_latest_404.json"))).latest())
    }
}
