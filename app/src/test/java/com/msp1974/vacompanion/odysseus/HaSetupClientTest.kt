package com.msp1974.vacompanion.odysseus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HaSetupClientTest {

    private data class Call(val method: String, val path: String, val bearer: String?, val body: String?)

    /** Answers by "METHOD path" (path without the HA base); records every call. */
    private class FakeHa(val routes: MutableMap<String, MutableList<HaResponse>>) : HaHttp {
        val calls = mutableListOf<Call>()
        override fun call(method: String, url: String, bearer: String?, contentType: String?, body: String?): HaResponse {
            val path = url.removePrefix(BASE)
            calls += Call(method, path, bearer, body)
            val list = routes["$method $path"] ?: return HaResponse(404, "")
            return if (list.size > 1) list.removeAt(0) else list[0]
        }
    }

    companion object { const val BASE = "http://ha.local:8123" }

    private fun client(vararg routes: Pair<String, String>): Pair<HaSetupClient, FakeHa> {
        val fake = FakeHa(routes.groupBy({ it.first }, { HaResponse(200, it.second) }).mapValues { it.value.toMutableList() }.toMutableMap())
        var t = 0L
        return HaSetupClient(BASE, fake, sleep = { t += it }, now = { t }) to fake
    }

    private val entries = "GET /api/config/config_entries/entry?domain=vaca"

    @Test
    fun exchangesTheCodeWithTheEchoClientId() {
        val (ha, fake) = client("POST /auth/token" to """{"access_token":"A","refresh_token":"R","expires_in":1800,"token_type":"Bearer"}""")
        val t = ha.exchangeCode("c0de", "http://192.168.1.50:10801/")
        assertEquals(HaTokens("A", "R", 1800), t)
        assertTrue(fake.calls[0].body!!.contains("client_id=http%3A%2F%2F192.168.1.50%3A10801%2F"))
        assertTrue(fake.calls[0].body!!.contains("grant_type=authorization_code"))
    }

    @Test
    fun badCodeIsAPlainError() {
        val fake = FakeHa(mutableMapOf("POST /auth/token" to mutableListOf(HaResponse(400, """{"error":"invalid_request"}"""))))
        try {
            HaSetupClient(BASE, fake).exchangeCode("x", "http://e:10801/"); fail()
        } catch (e: HaSetupException) {
            assertTrue(e.message!!.contains("invalid_request"))
        }
    }

    @Test
    fun addsTheEchoThroughTheUserStep() {
        val (ha, fake) = client(
            entries to "[]",
            "POST /api/config/config_entries/flow" to """{"type":"form","flow_id":"F1","step_id":"user"}""",
            "POST /api/config/config_entries/flow/F1" to """{"type":"create_entry","title":"VACA kitchen","result":{"entry_id":"E1","title":"VACA kitchen"}}""",
        )
        val e = ha.addSelf("TOK", "echo.tail1.ts.net", 10800, listOf("VACA kitchen"))
        assertEquals(HaSetupClient.Entry("E1", "VACA kitchen", created = true), e)
        assertTrue(fake.calls[1].body!!.contains("\"handler\":\"vaca\""))
        assertEquals("""{"host":"echo.tail1.ts.net","port":10800}""", fake.calls[2].body)
        assertTrue(fake.calls.all { it.bearer == "TOK" })
    }

    @Test
    fun rerunFindsTheExistingEntryAndAddsNothing() {
        val (ha, fake) = client(entries to """[{"entry_id":"E0","title":"VACA old","source":"user","disabled_by":null},
            {"entry_id":"E9","title":"VACA other","source":"ignore","disabled_by":null}]""")
        val e = ha.addSelf("TOK", "h", 10800, listOf("VACA kitchen", "VACA old"))
        assertEquals(HaSetupClient.Entry("E0", "VACA old", created = false), e)
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun alreadyConfiguredAbortUsesTheExistingEntry() {
        val (ha, _) = client(
            entries to "[]",
            entries to """[{"entry_id":"E2","title":"VACA kitchen","source":"zeroconf"}]""",
            "POST /api/config/config_entries/flow" to """{"type":"abort","reason":"already_configured","flow_id":"F"}""",
        )
        assertEquals("E2", ha.addSelf("TOK", "h", 10800, listOf("VACA kitchen")).entryId)
    }

    @Test
    fun cannotConnectIsFlagged() {
        val (ha, _) = client(
            entries to "[]",
            "POST /api/config/config_entries/flow" to """{"type":"form","flow_id":"F1","step_id":"user"}""",
            "POST /api/config/config_entries/flow/F1" to """{"type":"form","flow_id":"F1","step_id":"user","errors":{"base":"cannot_connect"}}""",
        )
        try {
            ha.addSelf("TOK", "echo.tail1.ts.net", 10800, listOf("VACA kitchen")); fail()
        } catch (e: HaSetupException) {
            assertTrue(e.cannotConnect)
        }
    }

    @Test
    fun forbiddenSaysAdminIsNeeded() {
        val fake = FakeHa(mutableMapOf(entries to mutableListOf(HaResponse(403, ""))))
        try {
            HaSetupClient(BASE, fake).addSelf("TOK", "h", 10800, listOf("x")); fail()
        } catch (e: HaSetupException) {
            assertTrue(e.message!!.contains("administrator"))
            assertFalse(e.cannotConnect)
        }
    }

    @Test
    fun optionsKeepTheDashboard() {
        val (ha, fake) = client(
            "POST /api/config/config_entries/options/flow" to """{"type":"form","flow_id":"O1","step_id":"init","data_schema":[
                {"name":"ha_url","type":"string","optional":true,"description":{"suggested_value":null}},
                {"name":"default_dashboard","type":"string","optional":true,"description":{"suggested_value":"/dash-kitchen"}}]}""",
            "POST /api/config/config_entries/options/flow/O1" to """{"type":"create_entry"}""",
        )
        ha.setHaUrlOption("TOK", "E1", "https://ha.tail1.ts.net")
        assertEquals("""{"handler":"E1"}""", fake.calls[0].body)
        assertEquals("""{"ha_url":"https://ha.tail1.ts.net","default_dashboard":"/dash-kitchen"}""", fake.calls[1].body)
    }

    @Test
    fun waitsForControlsThenSetsThem() {
        val (ha, fake) = client(
            "POST /api/template" to "[]",
            "POST /api/template" to """[{"entity_id":"select.vaca_k_wake_word","state":"Hey Jarvis","options":["None","Hey Athena"]}]""",
            "POST /api/services/select/select_option" to "[]",
            "POST /api/services/number/set_value" to "[]",
        )
        val e = ha.waitForEntities("TOK", "E1", 60_000) { it.wakeWord != null }
        assertEquals("select.vaca_k_wake_word", e.wakeWord?.entityId)
        assertTrue(fake.calls[0].body!!.contains("config_entry_id(e) == 'E1'"))
        ha.selectOption("TOK", "select.vaca_k_wake_word", "Hey Athena")
        ha.setNumber("TOK", "number.vaca_k_wake_word_threshold", 9.2)
        assertEquals("""{"entity_id":"select.vaca_k_wake_word","option":"Hey Athena"}""", fake.calls[2].body)
        assertEquals("""{"entity_id":"number.vaca_k_wake_word_threshold","value":9.2}""", fake.calls[3].body)
    }

    @Test
    fun waitingGivesUpAfterTheTimeout() {
        val (ha, fake) = client("POST /api/template" to "[]")
        val e = ha.waitForEntities("TOK", "E1", 10_000) { it.wakeWord != null }
        assertEquals(null, e.wakeWord)
        assertTrue(fake.calls.size in 5..7)
    }
}
