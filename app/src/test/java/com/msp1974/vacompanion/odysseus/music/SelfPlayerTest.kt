package com.msp1974.vacompanion.odysseus.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfPlayerTest {
    private val uuid = "0f1e2d3c-fict-ion0-al00-uuid00000001"
    private val clientId = "Zk3vQ-fictional-client-id"

    private fun c(
        id: String, name: String = id, area: String = "", type: String? = "player",
        playerId: String? = null, available: Boolean = true,
    ) = Candidate(
        entityId = "media_player.$id",
        ids = listOfNotNull(playerId?.let { listOf("music_assistant", it) }),
        name = name, area = area, type = type, available = available,
    )

    private val kitchen = c("kitchen_echo", "Kitchen Echo", "Kitchen", playerId = clientId)
    private val living = c("living", "Living Room", "Living Room", playerId = "ma_living01")
    private val all = listOf(kitchen, living)

    @Test
    fun idMatchIsTheNormalCase() {
        assertEquals(SelfMatch("media_player.kitchen_echo", How.ID), matchSelf(clientId, uuid, "Kitchen", null, all))
    }

    @Test
    fun aRenameKeepsTheIdMatch() {
        val renamed = listOf(kitchen.copy(entityId = "media_player.breakfast_bar", name = "Breakfast Bar", area = "Dining"), living)
        assertEquals(SelfMatch("media_player.breakfast_bar", How.ID), matchSelf(clientId, uuid, "Kitchen", null, renamed))
    }

    @Test
    fun idMustBeMusicAssistantsIdentifier() {
        val other = listOf(kitchen.copy(ids = listOf(listOf("sendspin", clientId))), living)
        assertNull(matchSelf(clientId, uuid, null, null, other))
    }

    @Test
    fun overrideWinsWhileItIsACandidate() {
        assertEquals(
            SelfMatch("media_player.living", How.OVERRIDE),
            matchSelf(clientId, uuid, "Kitchen", "media_player.living", all),
        )
    }

    @Test
    fun staleOverrideCarriesOnAndIsReported() {
        val m = matchSelf(clientId, uuid, "Kitchen", "media_player.gone", all)
        assertEquals(SelfMatch("media_player.kitchen_echo", How.ID, staleOverride = true), m)
        assertTrue(isStaleOverride("media_player.gone", all))
        assertFalse(isStaleOverride("media_player.living", all))
        assertFalse(isStaleOverride("", all))
        assertFalse(isStaleOverride(null, all))
    }

    @Test
    fun legacyIdOnlyWhileAvailable() {
        val legacy = c("old_echo", "Old Echo", playerId = "vaca-$uuid")
        assertEquals(
            SelfMatch("media_player.old_echo", How.LEGACY_ID),
            matchSelf("new-key-id", uuid, null, null, listOf(legacy, living)),
        )
        assertNull(matchSelf("new-key-id", uuid, null, null, listOf(legacy.copy(available = false), living)))
    }

    @Test
    fun aNewKeyFallsBackToTheDefaultName() {
        val byName = c("vaca_sendspin", "vaca $uuid sendspin player", playerId = "some-older-id")
        assertEquals(
            SelfMatch("media_player.vaca_sendspin", How.NAME),
            matchSelf("a-new-key-id", uuid, "Kitchen", null, listOf(byName, living)),
        )
    }

    @Test
    fun twoWithTheDefaultNameIsNoMatchByName() {
        val a = c("a", "VACA $uuid Sendspin Player")
        val b = c("b", "VACA $uuid Sendspin Player")
        assertNull(matchSelf(null, uuid, null, null, listOf(a, b)))
    }

    @Test
    fun aNewKeyFallsBackToTheOneEchoPlayerInTheArea() {
        val group = c("kitchen_group", "Kitchen Group", "Kitchen", type = "group")
        val echo = kitchen.copy(echo = true)
        assertEquals(
            SelfMatch("media_player.kitchen_echo", How.AREA),
            matchSelf("a-new-key-id", uuid, "kitchen", null, listOf(echo, living, group)),
        )
    }

    @Test
    fun theAreaGuessNeverClaimsATvOrAnotherSpeaker() {
        // The one player in the area is a TV: not this Echo.
        val tv = c("bedroom_tv", "Bedroom TV", "Bedroom", playerId = "roku_fictional01")
        assertNull(matchSelf("a-new-key-id", uuid, "Bedroom", null, listOf(tv, living)))
        assertNull(matchSelf("a-new-key-id", uuid, "Bedroom", null, listOf(tv, living), echoAreas = listOf("Bedroom")))
        // With this Echo's player beside it (no area of its own: the Echo's), the Echo's is the guess.
        val echo = c("bedroom_echo", "Bedroom Echo", "Bedroom", playerId = "some-other-id").copy(echo = true)
        assertEquals(
            SelfMatch("media_player.bedroom_echo", How.AREA),
            matchSelf("a-new-key-id", uuid, "Bedroom", null, listOf(tv, echo, living), echoAreas = listOf("Bedroom", "Kitchen")),
        )
    }

    @Test
    fun twoEchoPlayersInOneAreaIsNull() {
        val second = c("kitchen_echo_2", "Kitchen Echo 2", "Kitchen").copy(echo = true)
        assertNull(matchSelf("a-new-key-id", uuid, "Kitchen", null, listOf(kitchen.copy(echo = true), second, living)))
    }

    @Test
    fun twoEchosInTheAreaIsNoGuess() {
        val echo = kitchen.copy(echo = true)
        assertNull(matchSelf("a-new-key-id", uuid, "Kitchen", null, listOf(echo, living), echoAreas = listOf("kitchen", "Kitchen ")))
    }

    @Test
    fun nothingKnownIsNull() {
        assertNull(matchSelf(null, "", null, null, all))
        assertNull(matchSelf(null, uuid, "", null, all))
        assertNull(matchSelf(clientId, uuid, "Kitchen", null, emptyList()))
    }

    // Two Echos in one area: the AREA guess never claims the other one's Sendspin player.
    private val otherKey = "Q2xhc3NpY2FsLWZpY3Rpb25hbC1rZXktb2YtZWNoby1B".take(43)

    @Test
    fun areaGuessSkipsAnotherEchosSendspinPlayer() {
        assertEquals(43, otherKey.length)
        val echoA = c("echo_a", "Echo A", "Kitchen", playerId = otherKey)
        assertNull(matchSelf("this-echos-new-key", uuid, "Kitchen", null, listOf(echoA)))
        val legacyA = c("echo_a", "Echo A", "Kitchen", playerId = "vaca-another-uuid")
        assertNull(matchSelf(null, uuid, "Kitchen", null, listOf(legacyA)))
        val namedA = c("echo_a", "VACA another-uuid Sendspin Player", "Kitchen", playerId = "ma_x")
        assertNull(matchSelf(null, uuid, "Kitchen", null, listOf(namedA)))
        // A speaker beside it is never the guess either.
        val speaker = c("kitchen_speaker", "Kitchen Speaker", "Kitchen", playerId = "ma_kitchen01")
        assertNull(matchSelf("this-echos-new-key", uuid, "Kitchen", null, listOf(echoA, speaker)))
    }

    @Test
    fun theOnlyEchoInTheAreaClaimsAnEchoPlayerThereWhateverItsId() {
        // Home Assistant has one Echo in the Kitchen (this one): a Sendspin player there is this one's.
        val echoA = c("echo_a", "Echo A", "Kitchen", playerId = otherKey)
        assertEquals(
            SelfMatch("media_player.echo_a", How.AREA),
            matchSelf("this-echos-new-key", uuid, "Kitchen", null, listOf(echoA), echoAreas = listOf("Kitchen", "Bedroom")),
        )
    }

    @Test
    fun otherVacaPlayerShapes() {
        assertTrue(isOtherVacaPlayer(c("a", playerId = otherKey), emptySet()))
        assertFalse(isOtherVacaPlayer(c("a", playerId = otherKey), setOf(otherKey)))
        assertFalse(isOtherVacaPlayer(c("a", "Kitchen Speaker", playerId = "ma_kitchen01"), emptySet()))
        assertFalse(isOtherVacaPlayer(c("a", "Kitchen Speaker"), emptySet()))
        assertTrue(looksLikeVacaPlayer(c("a", "VACA x Sendspin Player")))
        assertFalse(looksLikeVacaPlayer(c("a", "Bedroom TV", playerId = "roku_fictional01")))
    }
}
