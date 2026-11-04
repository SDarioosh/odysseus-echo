package com.msp1974.vacompanion.odysseus

import com.msp1974.vacompanion.odysseus.EchoSetupRules.HaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class EchoSetupRulesTest {

    // ---- host choice

    @Test
    fun magicDnsNameComesFirst() {
        assertEquals(listOf("echo.tail1234.ts.net", "192.168.1.50"),
            EchoSetupRules.chooseHosts("100.101.102.103", "echo.tail1234.ts.net.", "192.168.1.50"))
    }

    @Test
    fun reverseLookupThatGaveBackTheIpIsIgnored() {
        assertEquals(listOf("192.168.1.50", "100.101.102.103"),
            EchoSetupRules.chooseHosts("100.101.102.103", "100.101.102.103", "192.168.1.50"))
    }

    @Test
    fun nameOdysseusWouldRefuseIsIgnored() {
        assertEquals(listOf("192.168.1.50", "100.101.102.103"),
            EchoSetupRules.chooseHosts("100.101.102.103", "echo.example.com", "192.168.1.50"))
    }

    @Test
    fun noTailscaleMeansLanIp() {
        assertEquals(listOf("10.0.0.7"), EchoSetupRules.chooseHosts(null, "ignored.ts.net", "10.0.0.7"))
    }

    @Test
    fun onlyTailscaleIpNeedsATypedName() {
        val hosts = EchoSetupRules.chooseHosts("100.64.0.9", null, null)
        assertEquals(listOf("100.64.0.9"), hosts)
        assertTrue(EchoSetupRules.hostNeedsName(hosts[0]))
        assertFalse(EchoSetupRules.hostNeedsName("192.168.1.50"))
        assertFalse(EchoSetupRules.hostNeedsName("echo.tail1234.ts.net"))
    }

    @Test
    fun noAddressesNoHosts() {
        assertTrue(EchoSetupRules.chooseHosts(null, null, null).isEmpty())
    }

    @Test
    fun ipRanges() {
        assertTrue(EchoSetupRules.isTailscaleIp("100.64.0.1"))
        assertTrue(EchoSetupRules.isTailscaleIp("100.127.255.254"))
        assertFalse(EchoSetupRules.isTailscaleIp("100.128.0.1"))
        assertTrue(EchoSetupRules.isPrivateIp("172.31.0.1"))
        assertFalse(EchoSetupRules.isPrivateIp("172.32.0.1"))
        assertFalse(EchoSetupRules.isPrivateIp("100.64.0.1"))
        assertFalse(EchoSetupRules.isPrivateIp("999.1.1.1"))
    }

    @Test
    fun hostNames() {
        assertEquals("echo.tail1.ts.net", EchoSetupRules.normalizeHostName(" Echo.Tail1.ts.net. "))
        assertNull(EchoSetupRules.normalizeHostName("bad_name.ts.net"))
        assertNull(EchoSetupRules.normalizeHostName("-x.ts.net"))
        assertNull(EchoSetupRules.normalizeHostName("10.0.0.1"))
        assertNull(EchoSetupRules.normalizeHostName("a b"))
        assertTrue(EchoSetupRules.isLinkableName("kitchen"))
        assertTrue(EchoSetupRules.isLinkableName("echo.home.arpa"))
        assertFalse(EchoSetupRules.isLinkableName("localhost"))
        assertFalse(EchoSetupRules.isLinkableName("echo.example.com"))
    }

    // ---- URL checks

    @Test
    fun baseUrls() {
        assertEquals("https://odysseus.tail1.ts.net", EchoSetupRules.normalizeBaseUrl("odysseus.tail1.ts.net/"))
        assertEquals("http://homeassistant.local:8123", EchoSetupRules.normalizeBaseUrl("homeassistant.local:8123"))
        assertEquals("https://ha.tail1.ts.net", EchoSetupRules.normalizeBaseUrl("HTTPS://HA.tail1.ts.net"))
        assertEquals("https://x.ts.net/api/chat/voice/v1", EchoSetupRules.normalizeBaseUrl("https://x.ts.net/api/chat/voice/v1/"))
        assertNull(EchoSetupRules.normalizeBaseUrl("ftp://x"))
        assertNull(EchoSetupRules.normalizeBaseUrl("javascript:alert(1)"))
        assertNull(EchoSetupRules.normalizeBaseUrl("https://user:pw@x.ts.net"))
        assertNull(EchoSetupRules.normalizeBaseUrl("https://x.ts.net/?a=1"))
        assertNull(EchoSetupRules.normalizeBaseUrl("https://x.ts.net/#f"))
        assertNull(EchoSetupRules.normalizeBaseUrl("https://x .ts.net"))
        assertNull(EchoSetupRules.normalizeBaseUrl(""))
        assertNull(EchoSetupRules.normalizeBaseUrl(null))
    }

    @Test
    fun odysseusOriginFromVoiceBase() {
        assertEquals("https://o.ts.net", EchoSetupRules.odysseusOrigin("https://o.ts.net/api/chat/voice/v1"))
    }

    @Test
    fun odysseusLinkCarriesAReturnUrlTheHubAccepts() {
        val code = "AbCdEfGhIjKlMnOpQrStUv"
        assertTrue(EchoSetupRules.isValidCode(code))
        val url = EchoSetupRules.odysseusLinkUrl("https://o.ts.net", "echo.tail1.ts.net", code, "Kitchen & Co")
        val q = EchoSetupRules.parseQuery(url)
        assertEquals("Kitchen & Co", q["name"])
        val ret = URI(q["return"])
        assertEquals("http", ret.scheme)
        assertEquals(10801, ret.port)
        assertEquals("echo.tail1.ts.net", ret.host)
        assertTrue(Regex("^/setup/[A-Za-z0-9_-]{16,64}/odysseus$").matches(ret.path))
        assertNull(ret.rawQuery)
        assertTrue(url.startsWith("https://o.ts.net/echo/link?"))
    }

    @Test
    fun haAuthorizeUrl() {
        val url = EchoSetupRules.haAuthorizeUrl("http://ha.local:8123", "192.168.1.50", "CODEcodeCODEcode12", "st/ate")
        val q = EchoSetupRules.parseQuery(url)
        assertTrue(url.startsWith("http://ha.local:8123/auth/authorize?"))
        assertEquals("code", q["response_type"])
        assertEquals("http://192.168.1.50:10801/", q["client_id"])
        assertEquals("http://192.168.1.50:10801/setup/CODEcodeCODEcode12/ha", q["redirect_uri"])
        assertEquals("st/ate", q["state"])
        // HA requires the redirect on the client_id's scheme, host and port.
        val c = URI(q["client_id"]); val r = URI(q["redirect_uri"])
        assertEquals(c.scheme to c.authority, r.scheme to r.authority)
    }

    // ---- forms

    @Test
    fun formParsing() {
        val f = EchoSetupRules.parseForm("token=abc%2Bdef&base_url=https%3A%2F%2Fo.ts.net&ha_url=&name=Living+room&token=second&bad&=x")
        assertEquals("abc+def", f["token"])
        assertEquals("https://o.ts.net", f["base_url"])
        assertEquals("", f["ha_url"])
        assertEquals("Living room", f["name"])
        assertEquals(4, f.size)
        assertTrue(EchoSetupRules.parseForm("").isEmpty())
        assertTrue(EchoSetupRules.parseForm("a=%ZZ").isEmpty())
    }

    @Test
    fun namesAndIds() {
        assertEquals("Living room", EchoSetupRules.cleanName("  Living \t room\u0007 "))
        assertEquals(40, EchoSetupRules.cleanName("x".repeat(80)).length)
        assertEquals("Living_room", EchoSetupRules.deviceIdFromName("Living room"))
        assertEquals("Kids-room", EchoSetupRules.deviceIdFromName("Kid's-room!"))
        assertTrue(EchoSetupRules.isPlausibleToken("ody_voice_0123456789abcdef"))
        assertFalse(EchoSetupRules.isPlausibleToken("has space here"))
        assertFalse(EchoSetupRules.isPlausibleToken("short"))
    }

    @Test
    fun escaping() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", EchoSetupRules.esc("<a href=\"x\">&'"))
    }

    // ---- entities

    private fun ent(id: String, name: String? = null, options: List<String> = emptyList(), state: String? = "x") =
        HaEntity(id, name, state, options)

    @Test
    fun matchesVacaControlsByKey() {
        val all = listOf(
            ent("select.vaca_kitchen_wake_word_engine", options = listOf("openwakeword", "microwakeword")),
            ent("select.vaca_kitchen_wake_word_sound", options = listOf("None", "Ding")),
            ent("select.vaca_kitchen_wake_word", options = listOf("None", "Hey Athena")),
            ent("switch.vaca_kitchen_screen_on_wake_word"),
            ent("number.vaca_kitchen_wake_word_threshold"),
            ent("number.vaca_kitchen_mic_gain"),
        )
        val m = EchoSetupRules.matchEntities(all)
        assertEquals("select.vaca_kitchen_wake_word", m.wakeWord?.entityId)
        assertEquals("select.vaca_kitchen_wake_word_engine", m.wakeWordEngine?.entityId)
        assertEquals("select.vaca_kitchen_wake_word_sound", m.wakeSound?.entityId)
        assertEquals("number.vaca_kitchen_wake_word_threshold", m.threshold?.entityId)
    }

    @Test
    fun matchesRenamedOrSuffixedEntities() {
        val m = EchoSetupRules.matchEntities(listOf(
            ent("select.vaca_kitchen_wake_word_2"),
            ent("select.my_sound", name = "VACA Kitchen Wake word sound"),
            ent("number.thr", name = "VACA Kitchen Wake word threshold"),
        ))
        assertEquals("select.vaca_kitchen_wake_word_2", m.wakeWord?.entityId)
        assertEquals("select.my_sound", m.wakeSound?.entityId)
        assertEquals("number.thr", m.threshold?.entityId)
        assertNull(m.wakeWordEngine)
    }

    @Test
    fun parsesTemplateOutput() {
        val list = EchoSetupRules.parseEntities(
            """[{"entity_id":"select.vaca_k_wake_word","name":"VACA k Wake word","state":"Hey Jarvis","options":["None","Hey Athena"]},
               {"entity_id":"number.vaca_k_wake_word_threshold","name":null,"state":"unavailable","options":null}]""")
        assertEquals(2, list.size)
        assertEquals(listOf("None", "Hey Athena"), list[0].options)
        assertFalse(EchoSetupRules.isAvailable(list[1]))
        assertTrue(EchoSetupRules.isAvailable(list[0]))
        assertTrue(EchoSetupRules.parseEntities("not json").isEmpty())
    }

    @Test
    fun picksOptionsLoosely() {
        assertEquals("Hey Athena", EchoSetupRules.pickOption(listOf("None", "Hey Athena"), "hey_athena"))
        assertEquals("Ding", EchoSetupRules.pickOption(listOf("None", "Ding"), "Ding"))
        assertNull(EchoSetupRules.pickOption(listOf("None"), "Ding"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun templateRejectsOddEntryIds() {
        EchoSetupRules.entitiesTemplate("x' }}{{ bad")
    }
}
